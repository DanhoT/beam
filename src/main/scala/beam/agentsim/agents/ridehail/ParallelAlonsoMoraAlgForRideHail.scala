package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import org.jgrapht.graph.DefaultEdge

import scala.collection.immutable.List
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Success, Failure}


class ParallelAlonsoMoraAlgForRideHail(
  demand: List[CustomerRequest],
  supply: List[VehicleAndSchedule],
  timeWindow: Map[MobilityServiceRequestType, Int],
  radius: Int,
  implicit val skimmer: BeamSkimmer
) {

  private def parallelBuildOfRTVGraph(): AlonsoMoraPoolingAlgForRideHail.RTVGraph = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val rTvG = AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
      Future.sequence(supply.withFilter(_.getFreeSeats > 0).map { v =>
        Future {
          val vertices = MListBuffer.empty[RTVGraphNode]
          val edges = MListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
          val finalRequestsList = MListBuffer.empty[RideHailTrip]
          demand
            .withFilter(
              x =>
                AlonsoMoraPoolingAlgForRideHail.getTimeDistanceAndCost(v.schedule.head, x.pickup).distance.get <= radius
            )
            .foreach(
              r =>
                AlonsoMoraPoolingAlgForRideHail
                  .getRidehailSchedule(timeWindow, v.schedule ++ List(r.pickup, r.dropoff)) match {
                  case Some(schedule) =>
                    val t = RideHailTrip(List(r), schedule)
                    finalRequestsList append t
                    if (!vertices.contains(v)) vertices.append(v)
                    vertices.append(r, t)
                    edges.append((r, t), (t, v))
                  case _ =>
              }
            )
          if (finalRequestsList.nonEmpty) {
            for (k <- 2 until v.getFreeSeats + 1) {
              var index = 1
              val kRequestsList = MListBuffer.empty[RideHailTrip]
              for (t1 <- finalRequestsList) {
                for (t2 <- finalRequestsList
                       .drop(index)
                       .withFilter(
                         x =>
                           !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                       )) {
                  AlonsoMoraPoolingAlgForRideHail
                    .getRidehailSchedule(
                      timeWindow,
                      v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff))
                    ) match {
                    case Some(schedule) =>
                      val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                      kRequestsList.append(t)
                      vertices.append(t)
                      t.requests.foldLeft(()) { case (_, r) => edges.append((r, t)) }
                      edges.append((t, v))
                    case _ =>
                  }
                }
                index += 1
              }
              finalRequestsList.appendAll(kRequestsList)
            }
          }
          (vertices, edges)
        }
      }) onComplete {
        case Success(result) =>
          result foreach { case (vertices, edges) =>
            vertices foreach (vertex => rTvG.addVertex(vertex))
            edges foreach { case (vertexSrc, vertexDst) => rTvG.addEdge(vertexSrc, vertexDst) }
          }
        case Failure(e) => println(e.getMessage)
      }
    rTvG
  }

  def greedyAssignment(): List[(RideHailTrip, VehicleAndSchedule, Int)] = {
    val rTvG = parallelBuildOfRTVGraph()
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    val C0: Int = timeWindow.foldLeft(0)(_ + _._2)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val Rok = MListBuffer.empty[CustomerRequest]
    val Vok = MListBuffer.empty[VehicleAndSchedule]
    val greedyAssignmentList = MListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Int)]
    for (k <- V to 1 by -1) {
      rTvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { trip =>
          (
            trip.asInstanceOf[RideHailTrip],
            rTvG
              .getEdgeTarget(
                rTvG
                  .outgoingEdgesOf(trip)
                  .asScala
                  .filter(e => rTvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
                  .head
              )
              .asInstanceOf[VehicleAndSchedule],
            trip.asInstanceOf[RideHailTrip].cost + demand.count(
              y => !(trip.asInstanceOf[RideHailTrip].requests map (_.person) contains y.person)
            ) * C0 / k
          )
        }
        .toList
        .sortBy(_._3)
        .foldLeft(()) {
          case (_, (trip, vehicle, cost)) =>
            if (!(trip.requests exists (r => Rok contains r)) &&
                !(Vok contains vehicle)) {
              Rok.appendAll(trip.requests)
              Vok.append(vehicle)
              greedyAssignmentList.append((trip, vehicle, cost))
            }
        }
    }
    greedyAssignmentList.toList
  }
}

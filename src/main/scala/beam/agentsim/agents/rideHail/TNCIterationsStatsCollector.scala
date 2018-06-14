package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor.UpdateRideHailStats
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try


/**
  * numberOfRides: -> passengers =1 (sum of rides)
  * customerWaitTime -> sum and average
  *
  * idleTimes = count in each bin according to how much time remaining
  * agent arrives in a time 1000 and leaves at time 2000
  * bin Size=100 -> count as idle in 10 bins (from 1000 to 2000)
  * idleTime[TAZId,binNumber] // bin 10, 11, 12,...19 we do +1
  *
  * @param sumOfRequestedRides Total number of ride requests
  * @param sumOfWaitingTimes   Sum of waiting times
  * @param sumOfIdlingVehicles total number of idling vehicles
  */
case class RideHailStatsEntry(sumOfRequestedRides: Long, sumOfWaitingTimes: Long, sumOfIdlingVehicles: Long) {

  def average(other: RideHailStatsEntry): RideHailStatsEntry = {
    RideHailStatsEntry((sumOfRequestedRides + other.sumOfRequestedRides) / 2, (sumOfWaitingTimes + other.sumOfWaitingTimes) / 2, (sumOfIdlingVehicles + other.sumOfIdlingVehicles) / 2)
  }
}



class TNCIterationsStatsCollector(eventsManager: EventsManager, beamServices: BeamServices, rideHailIterationHistoryActor: ActorRef) extends BasicEventHandler {
  val beamConfig = beamServices.beamConfig
  // TAZ level -> how to get as input here?
  private val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption

  private val log = LoggerFactory.getLogger(classOf[TNCIterationsStatsCollector])


  // timeBins -> number OfTimeBins input
  private val rideHailConfig = beamConfig.beam.agentsim.agents.rideHail
  private val timeBinSizeInSec = rideHailConfig.iterationStats.timeBinSizeInSec
  private val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSizeInSec).toInt + 1

  private val rideHailModeChoiceEvents = mutable.Map[String, ModeChoiceEvent]()
  private val rideHailEventsTuples = mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()
  private val rideHailLastEvent = mutable.Map[String, PathTraversalEvent]()
  private val vehicleIdlingBins = mutable.Map[String, mutable.Map[Int, String]]()
  private val vehicleActiveBins = mutable.Map[String, mutable.Map[String, mutable.Set[Int]]]()
  private val vehicles = mutable.Map[String, Boolean]()

  var rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]] = mutable.Map()

  eventsManager.addHandler(this)

  def tellHistoryToRideHailIterationHistoryActorAndReset(): Unit = {
    // TODO: send message to actor with collected data

    //println("Inside tellHistoryToRideHailIterationHistoryActor")
    updateStatsForIdlingVehicles()

    rideHailIterationHistoryActor ! UpdateRideHailStats(TNCIterationStats(rideHailStats, mTazTreeMap.get, timeBinSizeInSec, numberOfTimeBins))

    // printStats()

    rideHailStats = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    rideHailModeChoiceEvents.clear()

    rideHailLastEvent.clear()

    vehicleIdlingBins.clear()

    vehicleActiveBins.clear()

    rideHailEventsTuples.clear()

    vehicles.clear()
  }

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {

      case ModeChoiceEvent.EVENT_TYPE =>

        collectModeChoiceEvents(ModeChoiceEvent.apply(event))

      case PersonEntersVehicleEvent.EVENT_TYPE =>

        collectPersonEntersEvents(event.asInstanceOf[PersonEntersVehicleEvent])

      case PathTraversalEvent.EVENT_TYPE =>

        calculateStats(PathTraversalEvent.apply(event))

      case _ =>
    }
  }

  /*
    Calculating sumOfRides

    collectRides
    1. This method will collect all the ModeChoice events where the mode is 'rideHailing'
    2. Afterwards when a PathTraversal event occurs for the same vehicle with num_passengers = 1 we will find the tazId
      using coord from the PathTraversal event
   */
  private def collectModeChoiceEvents(event: ModeChoiceEvent): Unit = {

    val mode = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)

    if (mode.equals("ride_hailing")) {

      val personId = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      rideHailModeChoiceEvents.put(personId, event)
    }
  }

  private def collectPersonEntersEvents(personEntersVehicleEvent: PersonEntersVehicleEvent): Unit = {

    val personId = personEntersVehicleEvent.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON)
    val vehicleId = personEntersVehicleEvent.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)

    if (vehicleId.contains("rideHail")) {
      if(personId.contains("rideHailAgent")) vehicles.put(vehicleId, false)

      rideHailModeChoiceEvents.get(personId) match {
        case Some(modeChoiceEvent) =>

          rideHailEventsTuples.put(vehicleId, (modeChoiceEvent, personEntersVehicleEvent))

          rideHailModeChoiceEvents.remove(personId)
        case None =>
      }
    }
  }

  private def calculateStats(pathTraversalEvent: PathTraversalEvent): Unit = {

    processPathTraversalEvent(pathTraversalEvent)

    val mode = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
    val vehicleId = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)

    if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")) {

      vehicles.put(vehicleId, true)

      collectActiveVehicles(vehicleId, pathTraversalEvent)
    }
  }

  private def processPathTraversalEvent(pathTraversalEvent: PathTraversalEvent): Unit = {

    val vehicleId = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
    val numPassengers = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt


    rideHailEventsTuples.get(vehicleId) match {
      case Some(events) =>

        val modeChoiceEvent = events._1
        val personEntersVehicleEvent = events._2

        val startTime = modeChoiceEvent.getTime.toLong
        val endTime = personEntersVehicleEvent.getTime.toLong
        val waitingTime = endTime - startTime

        val binIndex = getTimeBin(startTime)
        val tazId = getStartTazId(pathTraversalEvent)

        val tazBins = rideHailStats.get(tazId) match {
          case Some(bins) => bins
          case None => mutable.ArrayBuffer.fill[Option[RideHailStatsEntry]](numberOfTimeBins)(None)
        }

        tazBins(binIndex) = tazBins(binIndex) match {
          case Some(entry) =>
            val sumOfWaitingTimes = if (numPassengers > 0) entry.sumOfWaitingTimes + waitingTime else entry.sumOfWaitingTimes
            val numOfRequestedRides = entry.sumOfRequestedRides + 1
            Some(entry.copy(sumOfRequestedRides = numOfRequestedRides,
              sumOfWaitingTimes = sumOfWaitingTimes))
          case None =>
            Some(RideHailStatsEntry(1, waitingTime, 0))
        }

        rideHailStats.put(tazId, tazBins)

        rideHailEventsTuples.remove(vehicleId)

      case None =>
    }
  }

  private def collectActiveVehicles(vehicleId: String, currentEvent: PathTraversalEvent) = {

    val startTazId = getStartTazId(currentEvent)
    val endTazId = getEndTazId(currentEvent)

    //log.debug(currentEvent.toString)

    val startTime = currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong
    val endTime = currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong



    val startBin = getTimeBin(startTime)
    val endingBin = getTimeBin(endTime)

    log.debug(s"startTazId($startTazId), endTazId($endTazId), startTime($startTime), endTime($endTime), numberOfPassengers(${currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)})")


    var idlingBins = vehicleIdlingBins.get(vehicleId) match {
      case Some(bins) => bins
      case None => mutable.Map[Int, String]()
    }


    val iB= rideHailLastEvent.get(vehicleId) match {

      case Some(lastEvent)  =>
        val endTazId = getEndTazId(lastEvent)
        val endTime = lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong
        val endingBin = getTimeBin(endTime)
        ((endingBin+1) until startBin).map((_,endTazId)).toMap

      case None =>
        if(startBin > 0) {
          (0 until startBin).map((_,startTazId)).toMap
        } else {
          Map[Int, String]()
        }
    }
    idlingBins ++= iB

    //    val allBins = ((startBin until endingBin).map((_,startTazId)).toMap + (endingBin-> endTazId)) ++ idlingBins

    vehicleIdlingBins.put(vehicleId, idlingBins)
    rideHailLastEvent.put(vehicleId, currentEvent)




    val tazVehicles = vehicleActiveBins.get(startTazId) match {
      case Some(vehicles) => vehicles
      case None => mutable.Map.empty[String, mutable.Set[Int]]
    }

    var activeBins = tazVehicles.get(vehicleId) match {
      case Some(bins) => bins
      case None => mutable.Set[Int]()
    }



    activeBins ++= (startBin to endingBin).toSet

    tazVehicles.put(vehicleId, activeBins)
    vehicleActiveBins.put(startTazId, tazVehicles)
  }

  private def updateStatsForIdlingVehicles(): Unit = {

    rideHailLastEvent.foreach(rhEvent => {
      val vehicleId = rhEvent._1
      var idlingBins = vehicleIdlingBins.get(vehicleId) match {
        case Some(bins) => bins
        case None => mutable.Map[Int, String]()
      }

      val lastEvent = rhEvent._2
      val endTazId = getEndTazId(lastEvent)
      val endTime = lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong
      val endingBin = getTimeBin(endTime)
      idlingBins ++= ((endingBin+1) until numberOfTimeBins).map((_,endTazId)).toMap

      vehicleIdlingBins.put(vehicleId, idlingBins)
    })

    val numAlwaysIdleVehicles = vehicles.count(!_._2)
    if(numAlwaysIdleVehicles > 0) log.warn(s"$numAlwaysIdleVehicles rideHail vehicles (out of ${vehicles.size}) were never moved during whole day.")

    rideHailStats.foreach { items =>

      val tazId = items._1
      val bins = items._2

      bins.zipWithIndex.foreach { bin =>

        val binIndex = bin._2
        val noOfIdlingVehicles = getNoOfIdlingVehicle(tazId, binIndex)

        bins(binIndex) = bin._1 match {
          case Some(entry) => Some(entry.copy(sumOfIdlingVehicles = noOfIdlingVehicles))
          case None => Some(RideHailStatsEntry(0, 0, noOfIdlingVehicles))
        }
      }
    }
  }

  private def getNoOfIdlingVehicle(tazId: String, binIndex: Int): Int = {
    vehicleIdlingBins.count(vehicle => {
      val bins = vehicle._2
      bins.get(binIndex) match {
        case Some(taz) if taz == tazId => true
        case _ => false
      }
    })

    /*val c = vehicleIdlingBins.count(vehicle => {
      val bins = vehicle._2
      bins.get(binIndex) match {
        case Some(_) => false
        case None =>
          val taz = bins.keySet.find(binIndex < _) match {
            case Some(first) => bins.get(first)
            case None => bins.values.lastOption
          }
          taz match {
            case Some(t) if t == tazId => true
            case _ => false
          }
      }
    })*/

/*
    val totalVehicles = vehicleActiveBins.flatMap(_._2.keySet).toSet.size
    vehicleActiveBins.get(tazId) match {
      case Some(vehBins) =>
        val noOfActiveVehicles = vehBins.count(_._2.contains(binIndex))
        totalVehicles - noOfActiveVehicles
      case None =>
        totalVehicles
    }*/
  }

  private def isSameCoords(currentEvent: PathTraversalEvent, lastEvent: PathTraversalEvent) = {
    val lastCoord = (lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X).toDouble,
      lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y).toDouble)
    val currentCoord = (currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble,
      currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble)
    lastCoord == currentCoord
  }

  private def getStartTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val startX = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble
    val startY = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble

    val coord = beamServices.geo.wgs2Utm(new Coord(startX, startY))

    val tazId = getTazId(coord)
    tazId
  }

  private def getEndTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val x = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X).toDouble
    val y = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y).toDouble

    val coord = beamServices.geo.wgs2Utm(new Coord(x, y))

    val tazId = getTazId(coord)
    tazId
  }

  private def getTazId(coord: Coord): String = {
    mTazTreeMap.get.getTAZ(coord.getX, coord.getY).tazId.toString
  }

  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }

  private def printStats(): Unit = {
    rideHailStats.foreach { rhs =>
      log.debug(rhs._1)
      rhs._2.map(_.toString).foreach(log.debug)
    }
  }

}

package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}

trait RepositionManager extends Actor with ActorLogging {

  override def receive: Receive = {
    case TriggerWithId(REPDataCollectionTrigger(tick), triggerId) =>
      currentTick = tick
      val nextTick = tick + statTime
      if (nextTick < eos) {
        collectData(tick)
        sender ! CompletionNotice(triggerId, Vector(ScheduleTrigger(REPDataCollectionTrigger(nextTick), self)))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case TriggerWithId(REPVehicleRepositionTrigger(tick), triggerId) =>
      val nextTick = tick + repTime
      if (nextTick < eos) {
        val vehForReposition =
          algorithm.getVehiclesForReposition(tick, repTime, queryAvailableVehicles)
        val triggers = vehForReposition
          .filter(rep => makeUnavailable(rep._1.id, rep._1.toStreetVehicle).isDefined)
          .map {
            case (vehicle, _, _, dstWhereWhen, dstTAZ) =>
              collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.pickup)
              ScheduleTrigger(REPVehicleTeleportTrigger(dstWhereWhen.time, dstWhereWhen, vehicle, dstTAZ), self)
          }
          .toVector
        sender ! CompletionNotice(triggerId, triggers :+ ScheduleTrigger(REPVehicleRepositionTrigger(nextTick), self))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case TriggerWithId(REPVehicleTeleportTrigger(_, whereWhen, vehicle, _), triggerId) =>
      makeTeleport(vehicle.id, whereWhen)
      makeAvailable(vehicle.id)
      collectData(vehicle.spaceTime.time, vehicle.spaceTime.loc, RepositionManager.dropoff)
      sender ! CompletionNotice(triggerId)
  }

  var currentTick = 0
  val eos = 108000

  val (algorithm, repTime, statTime) = getRepositionAlgorithmType match {
    case Some(algorithmType) =>
      getScheduler ! ScheduleTrigger(REPDataCollectionTrigger(algorithmType.getStatTimeBin), self)
      var alg: RepositionAlgorithm = null
      if (getServices.matsimServices.getIterationNumber > 0 || getServices.beamConfig.beam.warmStart.enabled) {
        alg = algorithmType.getInstance(getId, getServices, getSkimmer)
        getScheduler ! ScheduleTrigger(REPVehicleRepositionTrigger(algorithmType.getRepositionTimeBin), self)
      }
      (alg, algorithmType.getRepositionTimeBin, algorithmType.getStatTimeBin)
    case _ =>
      (null, 0, 0)
  }

  // ****
  def getId: Id[VehicleManager]
  def queryAvailableVehicles: List[BeamVehicle]
  def makeUnavailable(vehId: Id[BeamVehicle], streetVehicle: StreetVehicle): Option[BeamVehicle]
  def makeAvailable(vehId: Id[BeamVehicle]): Boolean
  def makeTeleport(vehId: Id[BeamVehicle], whenWhere: SpaceTime): Unit
  def getScheduler: ActorRef
  def getServices: BeamServices
  def getSkimmer: BeamSkimmer
  def getRepositionAlgorithmType: Option[RepositionAlgorithmType]

  def collectData(time: Int, coord: Coord, label: String) = {
    if (statTime != 0)
      getSkimmer.countEventsByTAZ(time / statTime, coord, getId, label)
  }

  def collectData(time: Int) = {
    if (statTime != 0)
      getSkimmer.observeVehicleAvailabilityByTAZ(
        time / statTime,
        getId,
        RepositionManager.availability,
        queryAvailableVehicles
      )
  }
}

case class REPVehicleRepositionTrigger(tick: Int) extends Trigger
case class REPDataCollectionTrigger(tick: Int) extends Trigger
case class REPVehicleTeleportTrigger(tick: Int, whereWhen: SpaceTime, vehicle: BeamVehicle, idTAZ: Id[TAZ])
    extends Trigger

trait RepositionAlgorithm {

  def getVehiclesForReposition(
    time: Int,
    timeBin: Int,
    availableFleet: List[BeamVehicle]
  ): List[(BeamVehicle, SpaceTime, Id[TAZ], SpaceTime, Id[TAZ])]
}
case class RepositionModule(algorithm: RepositionAlgorithm, timeBin: Int, statTimeBin: Int)

object RepositionManager {
  val pickup = "REPPickup"
  val dropoff = "REPDropoff"
  val inquiry = "VEHInquiry"
  val boarded = "VEHBoarded"
  val release = "VEHRelease"
  val availability = "VEHAvailability"
}

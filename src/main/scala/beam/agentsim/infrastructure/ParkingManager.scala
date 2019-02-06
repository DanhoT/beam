package beam.agentsim.infrastructure

import akka.actor.Actor
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ParkingStall.{ChargingPreference, ReservedParkingType}
import beam.router.BeamRouter.Location
import beam.sim.population.AttributesOfIndividual
import beam.utils.ParkingManagerIdGenerator
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import beam.agentsim.infrastructure.parking.charging.ChargingInquiryData.ChargingInquiryData

abstract class ParkingManager(
  parkingStockAttributes: ParkingStockAttributes
) extends Actor

object ParkingManager {

  case class ParkingInquiry(
    customerLocationUtm: Location,
    destinationUtm: Location,
    activityType: String,
    attributesOfIndividual: AttributesOfIndividual,
    chargingInquiryData: Option[ChargingInquiryData],
    arrivalTime: Long,
    parkingDuration: Double,
    reservedFor: ReservedParkingType = ParkingStall.Any,
    reserveStall: Boolean = true,
    requestId: Int = ParkingManagerIdGenerator.nextId
  )

  case class DepotParkingInquiry(
    vehicleId: Id[Vehicle],
    customerLocationUtm: Location,
    reservedFor: ReservedParkingType,
    requestId: Int = ParkingManagerIdGenerator.nextId
  )
  case class DepotParkingInquiryResponse(maybeStall: Option[ParkingStall], requestId: Int)

  case class ParkingInquiryResponse(stall: ParkingStall, requestId: Int)

  // Use this to pass data from CSV or config file into the manager
  case class ParkingStockAttributes(numSpacesPerTAZ: Int)

}

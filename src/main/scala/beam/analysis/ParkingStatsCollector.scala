package beam.analysis
import java.util

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events._
import beam.analysis.plots.{GraphAnalysis, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode
import beam.sim.{BeamServices, OutputDataDescription}
import beam.utils.{FileUtils, OutputDataDescriptor}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

/**
  * Collects the inbound and outbound parking overhead times and cost stats.
  *
  * @param beamServices an instance of beam services
  */
class ParkingStatsCollector(beamServices: BeamServices) extends GraphAnalysis with LazyLogging {

  // Stores the person and his outbound parking overhead related stats, only when the mode of choice is either car or drive_transit.
  private val personOutboundParkingStatsTracker
    : mutable.LinkedHashMap[Id[Person], ParkingStatsCollector.PersonOutboundParkingStats] =
    mutable.LinkedHashMap.empty[Id[Person], ParkingStatsCollector.PersonOutboundParkingStats]

  // Stores the person and his inbound parking overhead related stats, only when the mode of choice is either car or drive_transit.
  private val personInboundParkingStatsTracker
    : mutable.LinkedHashMap[Id[Person], ParkingStatsCollector.PersonInboundParkingStats] =
    mutable.LinkedHashMap.empty[Id[Person], ParkingStatsCollector.PersonInboundParkingStats]

  // Stores parking stats grouped by the time bin and parking taz
  private val parkingStatsByBinAndTaz: mutable.LinkedHashMap[(Int, String), ParkingStatsCollector.ParkingStats] =
    mutable.LinkedHashMap.empty[(Int, String), ParkingStatsCollector.ParkingStats]

  // Base name of the file that stores the output of the parking stats
  private val fileBaseName = "parkingStats"

  /**
    * Creates the required output analysis files at the end of an iteration.
    *
    * @param event an iteration end event.
    */
  override def createGraph(event: IterationEndsEvent): Unit = {
    //write the parking stats collected by time bin and parking TAZ to a csv file
    writeToCsv(event.getIteration, parkingStatsByBinAndTaz)
  }

  /**
    * Processes the collected stats on occurrence of the required events.
    *
    * @param event A beam event
    */
  override def processStats(event: Event): Unit = {

    event match {

      /*
               If the occurred event is a ModeChoiceEvent and when the mode of choice is either car or drive_transit
             start tracking the departing person
       */
      case modeChoiceEvent: ModeChoiceEvent =>
        val modeChoiceEventAttributes = modeChoiceEvent.getAttributes
        val modeChoice = Option(modeChoiceEventAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)).getOrElse("")
        modeChoice match {
          case BeamMode.CAR.value | BeamMode.DRIVE_TRANSIT.value =>
            // start tracking the person for outbound stats
            if (!personOutboundParkingStatsTracker.contains(modeChoiceEvent.getPersonId)) {
              personOutboundParkingStatsTracker.put(
                modeChoiceEvent.getPersonId,
                ParkingStatsCollector.EMPTY_PERSON_OUTBOUND_STATS
              )
            }
            // start tracking the person for inbound stats
            if (!personInboundParkingStatsTracker.contains(modeChoiceEvent.getPersonId)) {
              personInboundParkingStatsTracker.put(
                modeChoiceEvent.getPersonId,
                ParkingStatsCollector.EMPTY_PERSON_INBOUND_STATS
              )
            }
          case _ =>
        }

      /*
             If the occurred event is a PersonDepartureEvent and if the person is being tracked
             store the time of departure of the person.
       */
      case personDepartureEvent: PersonDepartureEvent =>
        // check if the person in the event is being tracked
        if (personOutboundParkingStatsTracker.contains(personDepartureEvent.getPersonId)) {
          val personParkingStats: ParkingStatsCollector.PersonOutboundParkingStats =
            personOutboundParkingStatsTracker.getOrElse(
              personDepartureEvent.getPersonId,
              ParkingStatsCollector.EMPTY_PERSON_OUTBOUND_STATS
            )
          //store the departure time of the person
          personOutboundParkingStatsTracker.put(
            personDepartureEvent.getPersonId,
            personParkingStats.copy(departureTime = Some(personDepartureEvent.getTime))
          )
        }

      /*
             If the occurred event is a PersonEntersVehicleEvent and if that vehicle is a transit vehicle
             stop tracking the person
       */
      case personEntersVehicleEvent: PersonEntersVehicleEvent =>
        if (personOutboundParkingStatsTracker.contains(personEntersVehicleEvent.getPersonId) && BeamVehicleType
              .isTransitVehicle(
                personEntersVehicleEvent.getVehicleId
              )) {
          //stop tracking the person
          personOutboundParkingStatsTracker.remove(personEntersVehicleEvent.getPersonId)
        }
        if (personInboundParkingStatsTracker.contains(personEntersVehicleEvent.getPersonId) && BeamVehicleType
              .isTransitVehicle(
                personEntersVehicleEvent.getVehicleId
              )) {
          //stop tracking the person
          personInboundParkingStatsTracker.remove(personEntersVehicleEvent.getPersonId)
        }

      /*
             If the occurred event is a LeavingParkingEvent and if the person is being tracked
             process the parking stats collected so far for that person
       */
      case leavingParkingEvent: LeavingParkingEvent =>
        if (personOutboundParkingStatsTracker.contains(leavingParkingEvent.getPersonId)) {
          // Get the parking TAZ from the event
          val leavingParkingEventAttributes = leavingParkingEvent.getAttributes
          val parkingTaz = Option(leavingParkingEventAttributes.get(LeavingParkingEventAttrs.ATTRIBUTE_PARKING_TAZ))
          val personOutboundParkingStats = personOutboundParkingStatsTracker.getOrElse(
            leavingParkingEvent.getPersonId,
            ParkingStatsCollector.EMPTY_PERSON_OUTBOUND_STATS
          )
          //save the parking taz to the inbound stats as well
          val personInboundParkingStats = personInboundParkingStatsTracker
            .getOrElse(
              leavingParkingEvent.getPersonId,
              ParkingStatsCollector.EMPTY_PERSON_INBOUND_STATS
            )
            .copy(parkingTAZ = parkingTaz)
          personInboundParkingStatsTracker.put(leavingParkingEvent.getPersonId, personInboundParkingStats)

          if (personOutboundParkingStats.departureTime.isDefined) {
            //process the collected inbound stats for the person
            processOutboundParkingStats(
              leavingParkingEvent.getPersonId.toString,
              personOutboundParkingStats
                .copy(leaveParkingTime = Some(leavingParkingEvent.getTime), parkingTAZ = parkingTaz)
            )
            //stop tracking the person for outbound stats
            personOutboundParkingStatsTracker.remove(leavingParkingEvent.getPersonId)
          }
        }

      /*
             If the occurred event is a ParkEvent and if the person is being tracked
             store the parking time and parking cost
       */
      case parkEvent: ParkEvent =>
        if (personInboundParkingStatsTracker.contains(Id.createPersonId(parkEvent.getDriverId))) {
          // get the parking cost from the event attributes
          val parkingCost: Option[Double] = try {
            Option(parkEvent.getAttributes.get(ParkEventAttrs.ATTRIBUTE_COST)).map(_.toDouble)
          } catch {
            case e: Exception =>
              logger.error("Error while reading cost attribute and converting it to double : " + e.getMessage, e)
              None
          }
          val personInboundParkingStats = personInboundParkingStatsTracker.getOrElse(
            Id.createPersonId(parkEvent.getDriverId),
            ParkingStatsCollector.EMPTY_PERSON_INBOUND_STATS
          )
          //store the parking time + parking cost for the person
          personInboundParkingStatsTracker.put(
            Id.createPersonId(parkEvent.getDriverId),
            personInboundParkingStats.copy(parkingTime = Some(parkEvent.getTime), parkingCost = parkingCost)
          )
        }

      /*
             If the occurred event is a PathTraversalEvent and if the person is being tracked is the vehicle driver
             process the parking stats collected so far for that person
       */
      case pathTraversalEvent: PathTraversalEvent =>
        val pathTraversalEventAttributes = pathTraversalEvent.getAttributes
        val driverId: Option[String] = Option(pathTraversalEventAttributes.get(PathTraversalEvent.ATTRIBUTE_DRIVER_ID))
        driverId match {
          case Some(dId) =>
            if (personInboundParkingStatsTracker.contains(Id.createPersonId(dId))) {
              val personInboundParkingStats = personInboundParkingStatsTracker.getOrElse(
                Id.createPersonId(dId),
                ParkingStatsCollector.EMPTY_PERSON_INBOUND_STATS
              )
              if (personInboundParkingStats.parkingTime.isDefined) {
                // Calculate the inbound parking overhead time
                val arrivalTime: Option[Double] = try {
                  Option(pathTraversalEventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)).map(_.toDouble)
                } catch {
                  case e: Exception =>
                    logger.error(
                      "Error while fetching and casting the parking cost attribute to double : " + e.getMessage,
                      e
                    )
                    None
                }
                //process the collected inbound stats for the person
                processInboundParkingStats(dId, personInboundParkingStats.copy(arrivalTime = arrivalTime))
                //stop tracking the person for inbound stats
                personInboundParkingStatsTracker.remove(Id.createPersonId(dId))
              }
            }
          case None =>
            logger.error(s"No driver id attribute defined for the PathTraversalEvent")
        }

      case _ =>
    }
  }

  /**
    * Processes the collected outbound parking stats of a person
    *
    * @param personOutboundParkingStats The outbound parking related stats of a person
    */
  private def processOutboundParkingStats(
    personId: String,
    personOutboundParkingStats: ParkingStatsCollector.PersonOutboundParkingStats
  ): Unit = {

    try {

      if (personOutboundParkingStats.leaveParkingTime.isDefined) {
        // Calculate the outbound parking overhead time
        val outboundParkingTime = personOutboundParkingStats.leaveParkingTime.get - personOutboundParkingStats.departureTime
          .getOrElse(0D)
        // Compute the hour of event
        val hourOfEvent = (personOutboundParkingStats.departureTime.get / 3600).toInt
        personOutboundParkingStats.parkingTAZ match {
          case Some(taz) =>
            //compute the outbound overhead time and add it to the cumulative stats grouped by hour + taz
            val parkingStats = parkingStatsByBinAndTaz.getOrElse(
              hourOfEvent -> taz,
              ParkingStatsCollector.ParkingStats(List.empty, List.empty, List.empty)
            )
            val outboundParkingTimes = parkingStats.outboundParkingTimeOverhead :+ outboundParkingTime
            parkingStatsByBinAndTaz.put(
              hourOfEvent -> taz,
              parkingStats.copy(outboundParkingTimeOverhead = outboundParkingTimes)
            )
          case None =>
            logger.error("No taz information available in the person outbound stats")
        }
      }
    } catch {
      case e: Exception => logger.error("Error while processing the outbound parking stats : " + e.getMessage, e)
    }
  }

  /**
    * Processes the collected outbound parking stats of a person
    *
    * @param personInboundParkingStats The outbound parking related stats of a person
    */
  private def processInboundParkingStats(
    personId: String,
    personInboundParkingStats: ParkingStatsCollector.PersonInboundParkingStats
  ): Unit = {

    try {

      if (personInboundParkingStats.arrivalTime.isDefined) {
        // Calculate the inbound parking overhead time
        val inboundParkingTime = personInboundParkingStats.arrivalTime.get - personInboundParkingStats.parkingTime
          .getOrElse(0D)
        // Compute the hour of event
        val hourOfEvent = (personInboundParkingStats.parkingTime.get / 3600).toInt
        personInboundParkingStats.parkingTAZ match {
          case Some(taz) =>
            //compute the outbound overhead time and add it to the cumulative stats grouped by hour + taz
            val parkingStats = parkingStatsByBinAndTaz.getOrElse(
              hourOfEvent -> taz,
              ParkingStatsCollector.ParkingStats(List.empty, List.empty, List.empty)
            )
            val inboundParkingTimes = parkingStats.inboundParkingTimeOverhead :+ inboundParkingTime
            val inboundParkingCosts = parkingStats.inboundParkingCostOverhead :+ personInboundParkingStats.parkingCost
              .getOrElse(0D)
            parkingStatsByBinAndTaz.put(
              hourOfEvent -> taz,
              parkingStats.copy(
                inboundParkingTimeOverhead = inboundParkingTimes,
                inboundParkingCostOverhead = inboundParkingCosts
              )
            )
          case None =>
            logger.error("No taz information available in the person inbound stats")
        }
      }
    } catch {
      case e: Exception => logger.error("Error while processing the inbound parking stats : " + e.getMessage, e)
    }
  }

  /**
    * Write the collected parking stats data to a csv file.
    *
    * @param iterationNumber the current iteration
    * @param parkingStatsByBinAndTaz parking overhead times grouped by the time bin and parking taz
    */
  private def writeToCsv(
    iterationNumber: Int,
    parkingStatsByBinAndTaz: mutable.LinkedHashMap[(Int, String), ParkingStatsCollector.ParkingStats]
  ): Unit = {
    try {
      val header = "timeBin,TAZ,outboundParkingOverheadTime,inboundParkingOverheadTime,inboundParkingOverheadCost"
      val csvFilePath =
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileBaseName + ".csv")
      val data = parkingStatsByBinAndTaz map {
        case ((bin, taz), parkingStats) =>
          val outboundParkingTime: Double = parkingStats.outboundParkingTimeOverhead match {
            case _ if parkingStats.outboundParkingTimeOverhead.isEmpty => 0D
            case _ if parkingStats.outboundParkingTimeOverhead.size == 1 =>
              parkingStats.outboundParkingTimeOverhead.head
            case _ => parkingStats.outboundParkingTimeOverhead.sum / parkingStats.outboundParkingTimeOverhead.size
          }
          val inboundParkingTime: Double = parkingStats.inboundParkingTimeOverhead match {
            case _ if parkingStats.inboundParkingTimeOverhead.isEmpty   => 0D
            case _ if parkingStats.inboundParkingTimeOverhead.size == 1 => parkingStats.inboundParkingTimeOverhead.head
            case _                                                      => parkingStats.inboundParkingTimeOverhead.sum / parkingStats.inboundParkingTimeOverhead.size
          }
          val inboundParkingCost: Double = parkingStats.inboundParkingCostOverhead match {
            case _ if parkingStats.inboundParkingCostOverhead.isEmpty   => 0D
            case _ if parkingStats.inboundParkingCostOverhead.size == 1 => parkingStats.inboundParkingCostOverhead.head
            case _                                                      => parkingStats.inboundParkingCostOverhead.sum / parkingStats.inboundParkingCostOverhead.size
          }
          bin + "," +
          taz + "," +
          outboundParkingTime + "," +
          inboundParkingTime + "," +
          inboundParkingCost
      } mkString "\n"
      FileUtils.writeToFile(csvFilePath, Some(header), data, None)
    } catch {
      case e: Exception => logger.error("Error while writing parking stats data to csv : " + e.getMessage, e)
    }
  }

  /**
    * Handles the post processing steps and resets the state.
    */
  override def resetStats(): Unit = {
    personOutboundParkingStatsTracker.clear()
    personInboundParkingStatsTracker.clear()
    parkingStatsByBinAndTaz.clear()
  }

}

object ParkingStatsCollector extends OutputDataDescriptor {

  case class ParkingStats(
    outboundParkingTimeOverhead: List[Double],
    inboundParkingTimeOverhead: List[Double],
    inboundParkingCostOverhead: List[Double]
  )

  case class PersonParkingStats(
    departureTime: Option[Double],
    parkingTime: Option[Double],
    parkingCost: Option[Double],
    parkingTAZId: Option[String]
  )

  case class PersonOutboundParkingStats(
    departureTime: Option[Double],
    leaveParkingTime: Option[Double],
    parkingTAZ: Option[String]
  )

  final val EMPTY_PERSON_OUTBOUND_STATS = PersonOutboundParkingStats(None, None, None)

  case class PersonInboundParkingStats(
    parkingTime: Option[Double],
    parkingCost: Option[Double],
    parkingTAZ: Option[String],
    arrivalTime: Option[Double]
  )
  final val EMPTY_PERSON_INBOUND_STATS = PersonInboundParkingStats(None, None, None, None)

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: util.List[OutputDataDescription] = {

    val outputFileBaseName = "parkingStats"
    val filePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, outputFileBaseName + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath: String = filePath.replace(outputDirPath, "")
    val outputDataDescription =
      OutputDataDescription(classOf[ParkingStatsCollector].getSimpleName.dropRight(1), relativePath, "", "")
    List(
      "timeBin"                     -> "Bin in a day",
      "TAZ"                         -> "Central point of the parking location",
      "outboundParkingOverheadTime" -> "Time taken by the person to depart , park vehicle and leave the parking area",
      "inboundParkingOverheadTime"  -> "Time taken by the person to walk from the parked car to the destination",
      "inboundParkingOverheadCost"  -> "Vehicle parking cost"
    ) map {
      case (header, description) =>
        outputDataDescription.copy(field = header, description = description)
    } asJava
  }
}

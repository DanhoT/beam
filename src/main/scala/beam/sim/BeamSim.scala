package beam.sim

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailIterationsStatsCollector}
import beam.analysis.plots.modality.ModalityStyleStats
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.analysis.via.ExpectedMaxUtilityHeatMap
import beam.analysis.{DelayMetricAnalysis, IterationStatsProvider, RideHailUtilizationCollector}
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.osm.TollCalculator
import beam.router.{BeamRouter, BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.sim.config.BeamConfig
import beam.sim.metrics.MetricsPrinter.{Print, Subscribe}
import beam.sim.metrics.{MetricsPrinter, MetricsSupport}
import beam.utils.csv.writers._
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scripts.FailFast
import beam.utils.{DebugLib, NetworkHelper}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.text.WordUtils
import org.jfree.data.category.DefaultCategoryDataset
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events._
import org.matsim.core.controler.listener.{
  IterationEndsListener,
  IterationStartsListener,
  ShutdownListener,
  StartupListener
}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BeamSim @Inject()(
  private val actorSystem: ActorSystem,
  private val transportNetwork: TransportNetwork,
  private val tollCalculator: TollCalculator,
  private val beamServices: BeamServices,
  private val eventsManager: EventsManager,
  private val scenario: Scenario,
  private val networkHelper: NetworkHelper,
  private val beamOutputDataDescriptionGenerator: BeamOutputDataDescriptionGenerator,
  private val beamSkimmer: BeamSkimmer,
  private val travelTimeObserved: TravelTimeObserved,
  private val beamConfigChangesObservable: BeamConfigChangesObservable,
  private val routeHistory: RouteHistory,
  private val rideHailIterationHistory: RideHailIterationHistory
) extends StartupListener
    with IterationStartsListener
    with IterationEndsListener
    with ShutdownListener
    with LazyLogging
    with MetricsSupport {

  private var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = _
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var createGraphsFromEvents: GraphsStatsAgentSimEventsListener = _
  private var delayMetricAnalysis: DelayMetricAnalysis = _
  private var modalityStyleStats: ModalityStyleStats = _
  private var expectedDisutilityHeatMapDataCollector: ExpectedMaxUtilityHeatMap = _

  private var tncIterationsStatsCollector: RideHailIterationsStatsCollector = _
  val iterationStatsProviders: ListBuffer[IterationStatsProvider] = new ListBuffer()
  val iterationSummaryStats: ListBuffer[Map[java.lang.String, java.lang.Double]] = ListBuffer()
  val graphFileNameDirectory = mutable.Map[String, Int]()
  var metricsPrinter: ActorRef = actorSystem.actorOf(MetricsPrinter.props())
  val summaryData = new mutable.HashMap[String, mutable.Map[Int, Double]]()

  val rideHailUtilizationCollector: RideHailUtilizationCollector = new RideHailUtilizationCollector(beamServices)

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      beamServices
    )

    metricsPrinter ! Subscribe("counter", "**")
    metricsPrinter ! Subscribe("histogram", "**")

    eventsManager.addHandler(rideHailUtilizationCollector)

    beamServices.beamRouter = actorSystem.actorOf(
      BeamRouter.props(
        beamServices.beamScenario,
        transportNetwork,
        scenario.getNetwork,
        networkHelper,
        beamServices.geo,
        scenario,
        scenario.getTransitVehicles,
        beamServices.fareCalculator,
        tollCalculator
      ),
      "router"
    )
    BeamWarmStart.warmStartTravelTime(
      beamServices.beamConfig,
      scenario.getConfig.travelTimeCalculator(),
      beamServices.beamRouter,
      scenario
    )
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    /*    if(null != beamServices.beamConfig.beam.agentsim.taz.file && !beamServices.beamConfig.beam.agentsim.taz.file.isEmpty)
          beamServices.taz = TAZTreeMap.fromCsv(beamServices.beamConfig.beam.agentsim.taz.file)*/

    if (!beamServices.beamConfig.beam.physsim.skipPhysSim) {
      agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(
        eventsManager,
        transportNetwork,
        event.getServices.getControlerIO,
        scenario,
        beamServices,
        beamConfigChangesObservable
      )
      iterationStatsProviders += agentSimToPhysSimPlanConverter
    }

    createGraphsFromEvents = new GraphsStatsAgentSimEventsListener(
      eventsManager,
      event.getServices.getControlerIO,
      beamServices,
      beamServices.beamConfig
    )
    iterationStatsProviders += createGraphsFromEvents
    modalityStyleStats = new ModalityStyleStats()
    expectedDisutilityHeatMapDataCollector = new ExpectedMaxUtilityHeatMap(
      eventsManager,
      beamServices.networkHelper,
      event.getServices.getControlerIO,
      beamServices.beamConfig.beam.outputs.writeEventsInterval
    )

    tncIterationsStatsCollector = new RideHailIterationsStatsCollector(
      eventsManager,
      beamServices,
      rideHailIterationHistory,
      transportNetwork
    )

    delayMetricAnalysis = new DelayMetricAnalysis(
      eventsManager,
      event.getServices.getControlerIO,
      networkHelper
    )

    val controllerIO = event.getServices.getControlerIO
    PopulationCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("population.csv"))
    VehiclesCsvWriter(beamServices).toCsv(scenario, controllerIO.getOutputFilename("vehicles.csv"))
    HouseholdsCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("households.csv"))
    NetworkCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("network.csv"))

    FailFast.run(beamServices)
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    beamConfigChangesObservable.notifyChangeToSubscribers()
    ExponentialLazyLogging.reset()
    beamServices.beamScenario.privateVehicles.values.foreach(_.initializeFuelLevels)

    val iterationNumber = event.getIteration

    val controllerIO = event.getServices.getControlerIO
    if (isFirstIteration(iterationNumber)) {
      PlansCsvWriter.toCsv(scenario, controllerIO.getOutputFilename("plans.csv"))
    }
    rideHailUtilizationCollector.reset(event.getIteration)

    if (shouldWritePlansAtCurrentIteration(event.getIteration)) {
      PlansCsvWriter.toCsv(scenario, controllerIO.getIterationFilename(iterationNumber, "plans_beg.csv"))
    }
  }

  private def shouldWritePlansAtCurrentIteration(iterationNumber: Int): Boolean = {
    val beamConfig: BeamConfig = beamConfigChangesObservable.getUpdatedBeamConfig
    val interval = beamConfig.beam.outputs.writePlansInterval
    interval > 0 && iterationNumber % interval == 0
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {

    val beamConfig: BeamConfig = beamConfigChangesObservable.getUpdatedBeamConfig

    travelTimeObserved.notifyIterationEnds(event)

    beamSkimmer.notifyIterationEnds(event)

    if (shouldWritePlansAtCurrentIteration(event.getIteration)) {
      PlansCsvWriter.toCsv(
        scenario,
        beamServices.matsimServices.getControlerIO.getIterationFilename(event.getIteration, "plans.csv")
      )
    }

    if (beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("notifyIterationEnds.start (after GC): "))

    rideHailUtilizationCollector.notifyIterationEnds(event)

    val outputGraphsFuture = Future {
      if ("ModeChoiceLCCM".equals(beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass)) {
        modalityStyleStats.processData(scenario.getPopulation, event)
        modalityStyleStats.buildModalityStyleGraph()
      }
      createGraphsFromEvents.createGraphs(event)

      iterationSummaryStats += iterationStatsProviders
        .flatMap(_.getSummaryStats.asScala)
        .toMap

      val summaryStatsFile = Paths.get(event.getServices.getControlerIO.getOutputFilename("summaryStats.csv")).toFile
      writeSummaryStats(summaryStatsFile)

      iterationSummaryStats.flatMap(_.keySet).distinct.foreach { x =>
        val key = x.split("_")(0)
        val value = graphFileNameDirectory.getOrElse(key, 0) + 1
        graphFileNameDirectory += key -> value
      }

      val fileNames = iterationSummaryStats.flatMap(_.keySet).distinct.sorted
      fileNames.foreach(file => createSummaryStatsGraph(file, event.getIteration))

      graphFileNameDirectory.clear()

      // rideHailIterationHistoryActor ! CollectRideHailStats
      tncIterationsStatsCollector
        .tellHistoryToRideHailIterationHistoryActorAndReset()

      if (beamConfig.beam.replanning.Module_2.equalsIgnoreCase("ClearRoutes")) {
        routeHistory.expireRoutes(beamConfig.beam.replanning.ModuleProbability_2)
      }
    }

    if (beamConfig.beam.physsim.skipPhysSim) {
      Await.result(Future.sequence(List(outputGraphsFuture)), Duration.Inf)
    } else {
      val physsimFuture = Future {
        agentSimToPhysSimPlanConverter.startPhysSim(event)
      }

      // executing code blocks parallel
      Await.result(Future.sequence(List(outputGraphsFuture, physsimFuture)), Duration.Inf)
    }

    if (beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("notifyIterationEnds.end (after GC): "))
    stopMeasuringIteration()

    val persons = scenario.getPopulation.getPersons.values().asScala
    logger.info(
      "Iteration {} - average number of plans per agent: {}",
      event.getIteration,
      persons.map(_.getPlans.size()).sum.toFloat / persons.size
    )

    val activityEndTimesNonNegativeCheck: Iterable[Plan] = persons.toList.flatMap(_.getPlans.asScala.toList) filter {
      plan =>
        val activities = plan.getPlanElements.asScala.filter(_.isInstanceOf[Activity])
        activities.dropRight(1).exists(_.asInstanceOf[Activity].getEndTime < 0)
    }

    if (activityEndTimesNonNegativeCheck.isEmpty) {
      logger.info("All person activities (except the last one) have non-negative end times.")
    } else {
      logger.warn(s"Non-negative end times found for person activities - ${activityEndTimesNonNegativeCheck.size}")
    }

    //    Tracer.currentContext.finish()
    metricsPrinter ! Print(
      Seq(
        "r5-plans-count"
      ),
      Nil
    )
    //rename output files generated by matsim to follow the standard naming convention of camel case
    renameGeneratedOutputFiles(event)

    if (beamConfig.beam.outputs.writeGraphs) {
      // generateRepositioningGraphs(event)
    }

    logger.info("Ending Iteration")
    delayMetricAnalysis.generateDelayAnalysis(event)
  }

  private def isFirstIteration(currentIteration: Integer): Boolean = {
    val firstIteration = beamServices.beamConfig.matsim.modules.controler.firstIteration
    currentIteration == firstIteration
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {

    val firstIteration = beamServices.beamConfig.matsim.modules.controler.firstIteration
    val lastIteration = beamServices.beamConfig.matsim.modules.controler.lastIteration

    logger.info("Generating html page to compare graphs (across all iterations)")
    BeamGraphComparator.generateGraphComparisonHtmlPage(event, firstIteration, lastIteration)
    beamOutputDataDescriptionGenerator.generateDescriptors(event)

    Await.result(actorSystem.terminate(), Duration.Inf)
    logger.info("Actor system shut down")

    // remove output files which are not ready for release yet (enable again after Jan 2018)
    val outputFilesToDelete = Array(
      "traveldistancestats.txt",
      "traveldistancestats.png",
      "tmp"
    )

    //rename output files generated by matsim to follow the standard naming convention of camel case
    val outputFiles = renameGeneratedOutputFiles(event)

    val scenario = event.getServices.getScenario
    val controllerIO = event.getServices.getControlerIO

    outputFilesToDelete.foreach(deleteOutputFile)

    def deleteOutputFile(fileName: String) = {
      logger.debug(s"deleting output file: $fileName")
      Files.deleteIfExists(Paths.get(controllerIO.getOutputFilename(fileName)))
    }
    BeamConfigChangesObservable.clear()

  }

  private def writeSummaryStats(summaryStatsFile: File): Unit = {
    val keys = iterationSummaryStats.flatMap(_.keySet).distinct.sorted

    val out = new BufferedWriter(new FileWriter(summaryStatsFile))
    out.write("Iteration,")
    out.write(keys.mkString(","))
    out.newLine()

    iterationSummaryStats.zipWithIndex.foreach {
      case (stats, it) =>
        out.write(s"$it,")
        out.write(
          keys
            .map { key =>
              stats.getOrElse(key, 0)
            }
            .mkString(",")
        )
        out.newLine()
    }

    out.close()
  }

  def createSummaryStatsGraph(fileName: String, iteration: Int): Unit = {
    val fileNamePath =
      beamServices.matsimServices.getControlerIO.getOutputFilename(fileName.replaceAll("[/: ]", "_") + ".png")
    val index = fileNamePath.lastIndexOf("/")
    val outDir = new File(fileNamePath.substring(0, index) + "/summaryStats")
    val directoryName = fileName.split("_")(0)
    val numberOfGraphs: Int = 10
    val directoryKeySet = graphFileNameDirectory.filter(_._2 >= numberOfGraphs).keySet

    if (!outDir.exists()) {
      Files.createDirectories(outDir.toPath)
    }

    if (directoryKeySet.contains(directoryName)) {
      directoryKeySet foreach { file =>
        if (file.equals(directoryName)) {
          val dir = new File(outDir.getPath + "/" + file)
          if (!dir.exists()) {
            Files.createDirectories(dir.toPath)
          }
          val path = dir.getPath + fileNamePath.substring(index)
          createGraph(iteration, fileName, path)
        }
      }
    } else {
      val path = outDir.getPath + fileNamePath.substring(index)
      createGraph(iteration, fileName, path)
    }

  }

  def createGraph(iteration: Int, fileName: String, path: String): Unit = {
    val doubleOpt = iterationSummaryStats(iteration).get(fileName)
    val value: Double = doubleOpt.getOrElse(0.0).asInstanceOf[Double]

    val dataset = new DefaultCategoryDataset

    var data = summaryData.getOrElse(fileName, new mutable.TreeMap[Int, Double])
    data += (iteration      -> value)
    summaryData += fileName -> data

    val updateData = summaryData.getOrElse(fileName, new mutable.TreeMap[Int, Double])

    updateData.foreach(x => dataset.addValue(x._2, 0, x._1))

    val fileNameTokens = fileName.replaceAll("[:/ ]", "_").split("_")
    var header = StringUtils.splitByCharacterTypeCamelCase(fileNameTokens(0)).map(_.capitalize).mkString(" ")
    if (fileNameTokens.size > 1) {
      header = header + "(" + fileNameTokens.slice(1, fileNameTokens.size).mkString("_") + ")"
    }

    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      header,
      "iteration",
      header,
      path,
      false
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      path,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  /**
    * Rename output files generated by libraries to match the standard naming convention of camel case.
    *
    * @param event Any controller event
    */
  private def renameGeneratedOutputFiles(event: ControlerEvent): Seq[File] = {
    val filesToBeRenamed: Array[File] = event match {
      case _ if event.isInstanceOf[IterationEndsEvent] =>
        val iterationEvent = event.asInstanceOf[IterationEndsEvent]
        val outputIterationFileNameRegex = List(s"legHistogram(.*)", "experienced(.*)")
        // filter files that match output file name regex and are to be renamed
        FileUtils
          .getFile(new File(event.getServices.getControlerIO.getIterationPath(iterationEvent.getIteration)))
          .listFiles()
          .filter(
            f =>
              outputIterationFileNameRegex.exists(
                f.getName
                  .replace(event.getServices.getIterationNumber.toInt + ".", "")
                  .matches(_)
            )
          )
      case _ if event.isInstanceOf[ShutdownEvent] =>
        val shutdownEvent = event.asInstanceOf[ShutdownEvent]
        val outputFileNameRegex = List("output(.*)")
        // filter files that match output file name regex and are to be renamed
        FileUtils
          .getFile(new File(shutdownEvent.getServices.getControlerIO.getOutputPath))
          .listFiles()
          .filter(f => outputFileNameRegex.exists(f.getName.matches(_)))
    }
    filesToBeRenamed
      .map { file =>
        //rename each file to follow the camel case
        val newFile = FileUtils.getFile(
          file.getAbsolutePath.replace(
            file.getName,
            WordUtils
              .uncapitalize(file.getName.split("_").map(_.capitalize).mkString(""))
          )
        )
        logger.info(s"Renaming file - ${file.getName} to follow camel case notation : " + newFile.getAbsoluteFile)
        try {
          if (file != newFile && !newFile.exists()) {
            file.renameTo(newFile)
          }
          newFile
        } catch {
          case e: Exception =>
            logger.error(s"Error while renaming file - ${file.getName} to ${newFile.getName}", e)
            file
        }
      }
  }

}

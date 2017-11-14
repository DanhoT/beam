package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.actor.{ActorRef, FSM, LoggingFSM}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.sim.monitoring.ErrorListener.{ErrorReasonResponse, RequestErrorReason}
import org.matsim.api.core.v01.Id

import scala.collection.mutable


object BeamAgent {

  // states
  trait BeamAgentState

  case object Abstain extends BeamAgentState

  case object Uninitialized extends BeamAgentState

  case object Initialized extends BeamAgentState

  case object AnyState extends BeamAgentState

  case object Finished extends BeamAgentState

  case object Error extends BeamAgentState

  sealed trait Info

  trait BeamAgentData

  case class BeamAgentInfo[T <: BeamAgentData](id: Id[_],
                                               implicit val data: T,
                                               triggerId: Option[Long] = None,
                                               tick: Option[Double] = None,
                                               errorReason: Option[String] = None) extends Info

  case class NoData() extends BeamAgentData

}

case class InitializeTrigger(tick: Double) extends Trigger


/**
  * This FSM uses [[BeamAgentState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  */
trait BeamAgent[T <: BeamAgentData] extends LoggingFSM[BeamAgentState, BeamAgentInfo[T]] {

  override def logDepth = 12

  def id: Id[_]

  def data: T

  protected implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Double] = None
  protected var listener: Option[ActorRef] = None

  private val chainedStateFunctions = new mutable.HashMap[BeamAgentState, mutable.Set[StateFunction]] with mutable.MultiMap[BeamAgentState, StateFunction]

  final def chainedWhen(stateName: BeamAgentState)(stateFunction: StateFunction): Unit = {
    chainedStateFunctions.addBinding(stateName, stateFunction)
  }

  def handleEvent(state: BeamAgentState, event: Event): State = {
    var theStateData = event.stateData
    event match {
      case Event(TriggerWithId(trigger, triggerId), _) =>
        theStateData = theStateData.copy(triggerId = Some(triggerId), tick = Some(trigger.tick))
      case Event(_, _) =>
      // do nothing
    }
    var theEvent = event.copy(stateData = theStateData)


    if (chainedStateFunctions.contains(state)) {
      var resultingBeamStates = List[BeamAgentState]()
      var resultingReplies = List[Any]()
      chainedStateFunctions(state).foreach { stateFunction =>
        if (stateFunction isDefinedAt theEvent) {
          val fsmState: State = stateFunction(theEvent)
          theStateData = fsmState.stateData.copy(triggerId = theStateData.triggerId, tick = theStateData.tick)
          theEvent = Event(event.event, theStateData)
          resultingBeamStates = resultingBeamStates :+ fsmState.stateName
          resultingReplies = resultingReplies ::: fsmState.replies
        }
      }
      val newStates = for (result <- resultingBeamStates if result != Abstain) yield result
      if (!allStatesSame(newStates)) {
        throw new RuntimeException(s"Chained when blocks did not achieve consensus on state to transition " +
          s" to for BeamAgent ${stateData.id}, newStates: $newStates, theEvent=$theEvent ,")
      } else if (newStates.isEmpty && state == AnyState) {
        val errMsg = s"Did not handle the event=${event.event.getClass}"
        logError(errMsg)
        FSM.State(Error, event.stateData.copy(errorReason = Some(errMsg)))
      } else if (newStates.isEmpty) {
        handleEvent(AnyState, event)
      } else {
        val numCompletionNotices = resultingReplies.count(_.isInstanceOf[CompletionNotice])
        if (numCompletionNotices > 1) {
          throw new RuntimeException(s"Chained when blocks attempted to reply with multiple CompletionNotices for BeamAgent ${stateData.id}")
        } else if (numCompletionNotices == 1) {
          theStateData = theStateData.copy(triggerId = None)
        }
        FSM.State(newStates.head, theStateData, None, None, resultingReplies)
      }
    } else {
      FSM.State(state, event.stateData)
    }
  }

  def numCompletionNotices(theReplies: List[Any]): Int = {
    theReplies.count(_.isInstanceOf[CompletionNotice])
  }

  def allStatesSame(theStates: List[BeamAgentState]): Boolean = {
    theStates.forall(stateToTest => stateToTest == theStates.head)
  }

  startWith(Uninitialized, BeamAgentInfo[T](id, data))

  when(Uninitialized) {
    case ev@Event(SubscribeTransitionCallBack(actorRef), _) =>
      listener = Some(actorRef)
      // send current state back as reference point
      actorRef ! CurrentState(self, this.stateName)
      stay()

    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }
  when(Initialized) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }

  when(Finished) {
    case Event(StopEvent, _) =>
      goto(Uninitialized)
  }

  when(Error) {
    case Event(RequestErrorReason, info) =>
      sender() ! ErrorReasonResponse(info.errorReason, _currentTick, getLog)
      stay()
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
  }

  whenUnhandled {
    case ev@Event(_, _) =>
      val result = handleEvent(AnyState, ev)
      if (result.stateName == AnyState) {
        logWarn(s"Unrecognized event ${ev.event}")
        stay()
      } else {
        result
      }
    case msg@_ =>
      val errMsg = s"Unrecognized message ${msg}"
      logError(errMsg)
      goto(Error) using stateData.copy(errorReason = Some(errMsg))
  }

  /*
   * Helper methods
   */
  def holdTickAndTriggerId(tick: Double, triggerId: Long) = {
    if (_currentTriggerId.isDefined || _currentTick.isDefined)
      throw new IllegalStateException(s"Expected both _currentTick and _currentTriggerId to be 'None' but found ${_currentTick} and ${_currentTriggerId} instead, respectively.")

    _currentTick = Some(tick)
    _currentTriggerId = Some(triggerId)
  }

  def releaseTickAndTriggerId(): (Double, Long) = {
    val theTuple = (_currentTick.get, _currentTriggerId.get)
    _currentTick = None
    _currentTriggerId = None
    theTuple
  }

  def logPrefix(): String

  def logWithFullPrefix(msg: String): String = {
    val tickStr = _currentTick match {
      case Some(theTick) =>
        s"Tick:${theTick.toString} "
      case None =>
        ""
    }
    val triggerStr = _currentTriggerId match {
      case Some(theTriggerId) =>
        s"TriggId:${theTriggerId.toString} "
      case None =>
        ""
    }
    s"$tickStr${triggerStr}State:$stateName ${logPrefix()}$msg"
  }

  def logInfo(msg: String): Unit = {
    log.info(s"${logWithFullPrefix(msg)}")
  }

  def logWarn(msg: String): Unit = {
    log.warning(s"${logWithFullPrefix(msg)}")
  }

  def logError(msg: String): Unit = {
    log.error(s"${logWithFullPrefix(msg)}")
  }

  def logDebug(msg: String): Unit = {
    log.debug(s"${logWithFullPrefix(msg)}")
  }

}


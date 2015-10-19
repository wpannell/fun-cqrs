package io.strongtyped.funcqrs.akka

import akka.actor._
import akka.pattern.pipe
import akka.persistence._
import io.strongtyped.funcqrs.akka.AggregateActor._
import io.strongtyped.funcqrs.{Aggregate, Behavior, DomainCommand, DomainEvent}
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class AggregateActor[A <: Aggregate](identifier: A#Id,
                                     behavior: Behavior[A],
                                     inactivityTimeout: Option[Duration] = None) extends PersistentActor with ActorLogging {

  import context.dispatcher

  type Protocol = A#Protocol

  // persistenceId is always defined as the Aggregate.Identifier
  val persistenceId = identifier.value

  /** The aggregate instance if initialized, None otherwise */
  private var aggregateOpt: Option[A] = None

  /**
   * The lifecycle of the aggregate, by default [[Uninitialized]]
   */
  protected var state: State = Uninitialized

  private var eventsSinceLastSnapshot = 0

  // always compose with defaultReceive
  override def receiveCommand: Receive = initializing orElse defaultReceive

  /**
   * PartialFunction to handle commands when the Actor is in the [[Uninitialized]] state
   */
  protected def initializing: Receive = {
    // always compose with defaultReceive
    initialReceive orElse defaultReceive
  }

  protected def initialReceive: Receive = {

    case cmd: Protocol#ProtocolCommand =>
      log.debug(s"Received creation cmd: $cmd")
      val eventualEvent = behavior.validate(cmd)
      val origSender = sender()

      eventualEvent map {
        event => CompletedCreationCmd(event, origSender)
      } recover {
        case NonFatal(cause) =>
          log.error(cause, s"Error while processing creational command: $cmd")
          FailedCommand(cause, origSender)
      } pipeTo self

      changeState(Busy)

  }


  /**
   * PartialFunction to handle commands when the Actor is in the [[Available]] state
   */
  protected def available: Receive = {
    // always compose with defaultReceive
    availableReceive orElse defaultReceive
  }

  protected def availableReceive: Receive = {

    case cmd: Protocol#ProtocolCommand =>
      log.debug(s"Received cmd: $cmd")
      val eventualEvents = behavior.validate(cmd, aggregateOpt.get)
      val origSender = sender()

      eventualEvents.map {
        events => CompletedUpdateCmd(events, origSender)
      } recover {
        case NonFatal(cause) =>
          log.error(cause, s"Error while processing update command: $cmd")
          FailedCommand(cause, origSender)
      } pipeTo self

      changeState(Busy)
  }

  def onCommandFailure(failedCmd: FailedCommand): Unit = {
    failedCmd.origSender ! Status.Failure(failedCmd.cause)
    changeState(Available)
  }

  private def busy: Receive = {

    case GetState                     => respond()
    case result: CompletedCreationCmd => onSuccessfulCreation(result)
    case result: CompletedUpdateCmd   => onSuccessfulUpdate(result)
    case failedCmd: FailedCommand     => onCommandFailure(failedCmd)

    case anyOther =>
      log.debug(s"received $anyOther while processing another command")
      stash()
  }

  protected def defaultReceive: Receive = {
    case GetState => respond()
  }


  /**
   * This method should be used as a callback handler for persist() method.
   * It will:
   * - apply the event on the aggregate effectively changing its state
   * - check if a snapshot needs to be saved.
   * @param evt DomainEvent that has been persisted
   */
  protected def afterEventPersisted(evt: Protocol#ProtocolEvent): Unit = {

    aggregateOpt = applyEvent(evt)

    eventsSinceLastSnapshot += 1

    if (eventsSinceLastSnapshot >= eventsPerSnapshot) {
      log.debug(s"$eventsPerSnapshot events reached, saving snapshot")
      saveSnapshot((state, aggregateOpt))
      eventsSinceLastSnapshot = 0
    }
  }

  /**
   * send a message containing the aggregate's state back to the requester
   * @param replyTo actor to send message to (by default the sender from where you received a command)
   */
  protected def respond(replyTo: ActorRef = context.sender()): Unit = {
    aggregateOpt match {
      case Some(data) => replyTo ! data
      case None       => Status.Failure(new NoSuchElementException(s"aggregate $persistenceId not initialized"))
    }
  }

  /**
   * Apply event on the AggregateRoot.
   *
   * Creational events are only applied if Aggregate is not yet initialized (ie: None)
   * Update events are only applied on already initialized Aggregates (ie: Some(aggregate))
   *
   * All other combinations will be ignored and the current Aggregate state is returned.
   */
  def applyEvent(event: DomainEvent): Option[A] = {

    (aggregateOpt, event) match {

      // apply CreateEvent if not yet initialized
      case (None, evt: Protocol#ProtocolEvent) => Some(behavior.applyEvent(evt))

      // Update events are applied on current state
      case (Some(aggregate), evt: Protocol#ProtocolEvent) => Some(behavior.applyEvent(evt, aggregate))

      // Covers:
      // (Some, CreateEvent) and (None, UpdateEvent)
      // in both cases we must ignore it and return current state
      case _ => aggregateOpt
    }
  }

  /**
   * Recovery handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing persistent actor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   */
  override val receiveRecover: Receive = {

    case SnapshotOffer(metadata, (state: State, data: Option[A])) =>
      eventsSinceLastSnapshot = 0
      log.debug("recovering aggregate from snapshot")
      restoreState(metadata, state, data)

    case SaveSnapshotSuccess(metadata) =>
      log.debug("snapshot saved")

    case RecoveryCompleted =>
      log.debug(s"aggregate '$persistenceId' has recovered, state = '$state'")

    case event: DomainEvent => onEvent(event)

    case unknown => log.debug(s"Unknown message on recovery")
  }


  protected def onEvent(evt: DomainEvent): Unit = {
    log.debug(s"Reapplying event $evt")
    eventsSinceLastSnapshot += 1
    aggregateOpt = applyEvent(evt)
    log.debug(s"State after event $aggregateOpt")
    changeState(Available)
  }

  /**
   * restore the lifecycle and state of the aggregate from a snapshot
   * @param metadata snapshot metadata
   * @param state the state of the aggregate
   * @param data the data of the aggregate
   */
  protected def restoreState(metadata: SnapshotMetadata, state: State, data: Option[A]) = {
    changeState(state)
    log.debug(s"restoring data $data")
    aggregateOpt = data
  }

  def changeState(state: State): Unit = {
    this.state = state
    this.state match {
      case Uninitialized =>
        log.debug(s"Initializing")
        context become initializing
        unstashAll() // actually not need, but we never know :-)

      case Available =>
        log.debug(s"Accepting commands...")
        context become available
        unstashAll()

      case Busy =>
        log.debug(s"Busy, only answering to GetState and command results.")
        context become busy
    }
  }

  /**
   * When a Creation Command completes we must:
   * - persist the event
   * - apply the event, ie: create the aggregate
   * - notify the original sender
   */
  private def onSuccessfulCreation(result: CompletedCreationCmd): Unit = {
    persist(result.event) { evt =>
      afterEventPersisted(evt)
    }
    result.origSender ! SuccessfulCommand(Seq(result.event))
    changeState(Available)
  }


  /**
   * When a Update Command completes we must:
   * - persist the events
   * - apply the events to the current aggregate state
   * - notify the original sender
   */
  private def onSuccessfulUpdate(result: CompletedUpdateCmd): Unit = {
    val events = immutable.Seq(result.events).flatten
    persistAll(events) { evt =>
      afterEventPersisted(evt)
    }
    result.origSender ! SuccessfulCommand(result.events)
    changeState(Available)
  }

  override def preStart() {
    inactivityTimeout.foreach { t =>
      log.debug(s"Setting timeout to $t")
      context.setReceiveTimeout(t)
    }
  }

  override def unhandled(message: Any) = {
    message match {
      case ReceiveTimeout =>
        log.info("Stopping")
        context.stop(self)
      case _              => super.unhandled(message)
    }
  }


  /**
   * Internal representation of a completed create command.
   */
  private case class CompletedCreationCmd(event: Protocol#ProtocolEvent, origSender: ActorRef)

  /**
   * Internal representation of a completed update command.
   */
  private case class CompletedUpdateCmd(events: Seq[Protocol#ProtocolEvent], origSender: ActorRef)

  private case class FailedCommand(cause: Throwable, origSender: ActorRef)

}


object AggregateActor {

  /**
   * state of Aggregate Root
   */
  sealed trait State

  case object Uninitialized extends State

  case object Available extends State

  case object Busy extends State

  /**
   * We don't want the aggregate to be killed if it hasn't fully restored yet,
   * thus we need some non AutoReceivedMessage that can be handled by akka persistence.
   */
  case object KillAggregate

  case object GetState extends DomainCommand

  case class SuccessfulCommand(events: Seq[DomainEvent])


  /**
   * Specifies how many events should be processed before new snapshot is taken.
   * TODO: make configurable
   */
  val eventsPerSnapshot = 10

}
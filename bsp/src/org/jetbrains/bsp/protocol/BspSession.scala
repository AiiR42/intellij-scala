package org.jetbrains.bsp.protocol

import java.io.{InputStream, OutputStream}
import java.util.concurrent.{Callable, CompletableFuture, LinkedBlockingQueue, TimeUnit}

import ch.epfl.scala.bsp4j
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.jsonrpc.{Launcher, ResponseErrorException}
import org.jetbrains.bsp._
import org.jetbrains.bsp.protocol.BspNotifications._
import org.jetbrains.bsp.protocol.BspSession._

import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.concurrent.duration._
import scala.io.Source
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class BspSession private(bspIn: InputStream,
                         bspErr: InputStream,
                         bspOut: OutputStream,
                         initializeBuildParams: bsp4j.InitializeBuildParams,
                         cleanup: ()=>Unit,
                         notificationCallbacks: List[NotificationCallback],
                         initialJob: BspSessionJob[_,_]
                        ) {

  private val logger = Logger.getInstance(classOf[BspCommunication])

  private val jobs = new LinkedBlockingQueue[BspSessionJob[_,_]]

  private var currentJob: BspSessionJob[_,_] = initialJob

  private var lastProcessOutput: Long = 0

  private val serverConnection: ServerConnection = startServerConnection
  private val sessionInitialized = initializeSession
  private val sessionShutdown = Promise[Unit]

  private val queuePause = 10.millis
  private val queueTimeout = 1.second
  private val sessionTimeout = 20.seconds

  private val queueProcessor = AppExecutorUtil.getAppScheduledExecutorService
      .scheduleWithFixedDelay(() => nextQueuedCommand, queuePause.toMillis, queuePause.toMillis, TimeUnit.MILLISECONDS)

  private def notifications(notification: BspNotification): Unit =
    notificationCallbacks.foreach(_.apply(notification))

  private def nextQueuedCommand= {
    try {
      waitForSession(sessionTimeout)
    } catch {
      case to : TimeoutException =>
        val error = BspConnectionError("bsp server is not responding", to)
        logger.warn(error)
        shutdown(Some(error))
      case NonFatal(error) =>
        val msg = s"problem connecting to bsp server: ${error.getMessage}. See IDE log for details."
        val bspError = BspException(msg, error)
        logger.warn(bspError)
        shutdown(Some(bspError))
    }

    try {
      currentJob.run(serverConnection.server) // in case not yet running
      val currentIgnoringErrors = currentJob.future.recover {
        case NonFatal(_) => ()
      }(ExecutionContext.global)
      Await.result(currentIgnoringErrors, queueTimeout) // will throw on job error

      val next = jobs.poll(queueTimeout.toMillis, TimeUnit.MILLISECONDS)
      if (next != null) {
        currentJob = next
        currentJob.run(serverConnection.server)
      }
    } catch {
      case _: TimeoutException => // just carry on
      case NonFatal(error) =>
        val bspError = BspException("problem executing bsp job", error)
        logger.error(bspError)
        currentJob.cancelWithError(bspError)
    }
  }

  private def startServerConnection: ServerConnection = {

    val localClient = new BspSessionClient

    val launcher = new Launcher.Builder[BspServer]()
      .setRemoteInterface(classOf[BspServer])
      .setExecutorService(AppExecutorUtil.getAppExecutorService)
      .setInput(bspIn)
      .setOutput(bspOut)
      .setLocalService(localClient)
      .create()
    val listening = launcher.startListening()
    val bspServer = launcher.getRemoteProxy
    localClient.onConnectWithServer(bspServer)

    val messageHandler = new BspProcessMessageHandler(bspErr)
    val messageHandlerRunning = AppExecutorUtil.getAppExecutorService.submit(messageHandler)

    val cancelable = Cancelable { () =>
      Cancelable.cancelAll(
        List(
          Cancelable(() => bspIn.close()),
          Cancelable(() => bspOut.close()),
          Cancelable(() => listening.cancel(true)),
          Cancelable(() => messageHandlerRunning.cancel(true)),
          Cancelable(() => cleanup())
        )
      )
    }

    ServerConnection(bspServer, cancelable)
  }

  private def initializeSession: CompletableFuture[bsp4j.InitializeBuildResult] = {
    val bspServer = serverConnection.server
    bspServer.buildInitialize(initializeBuildParams)
      .thenApply[bsp4j.InitializeBuildResult] { result =>
        bspServer.onBuildInitialized()
        result
      }
      .exceptionally {
        case NonFatal(error) => throw BspConnectionError(error.getMessage, error)
      }
  }

  private def waitForSession(timeout: Duration): bsp4j.InitializeBuildResult = try {
    sessionInitialized.get(timeout.toMillis, TimeUnit.MILLISECONDS)
  } catch {
    case to: TimeoutException =>
      val now = System.currentTimeMillis()
      val waited = now - lastProcessOutput
      if (waited > timeout.toMillis)
        throw BspConnectionError("bsp server is not responding", to)
      else
        waitForSession(timeout)
  }

  /** Run a task with client in this session.
    * Notifications during run of this task are passed to the aggregator. This can also be used for plain callbacks.
    */
  private[protocol] def run[T, A](job: BspSessionJob[T,A]): BspJob[(T,A)] = {
    val resultJob = if (isAlive) {
      job
    } else {
      new FailedBspSessionJob[T, A](BspException("BSP session is not available", deathReason.orNull))
    }
    jobs.put(resultJob)
    resultJob
  }

  private[protocol] def isAlive: Boolean = {
    ! sessionShutdown.isCompleted &&
      ! queueProcessor.isDone
  }

  private def deathReason = {
    val sessionError = sessionShutdown.future.value.flatMap {
      case Success(_) => None
      case Failure(exception) => Some(exception)
    }
    val queueError = Try(queueProcessor.get()) match {
      case Success(_) => None
      case Failure(exception) => Some(exception)
    }

    sessionError.orElse(queueError)
  }

  def shutdown(error: Option[BspError] = None): Try[Unit] = {
    def whenDone: CompletableFuture[Unit] = {
      serverConnection.server.buildShutdown()
        .thenApply[Unit](_=>())
        .whenComplete {(_, error) =>
          error match {
            case err: ResponseErrorException =>
              val msg = err.getMessage
              val errorObject = err.getResponseError
              BSP.balloonNotification.createNotification(msg, NotificationType.ERROR)
              val fullMessage = s"$msg (code ${errorObject.getCode}). Data: ${errorObject.getData}"
              logger.error(fullMessage)
          }
        }
    }

    error match {
      case None =>
        sessionShutdown.success(())
        currentJob.cancel()
        jobs.forEach(_.cancel())
      case Some(err) =>
        sessionShutdown.failure(err)
        currentJob.cancelWithError(err)
        jobs.forEach(_.cancelWithError(err))
    }
    queueProcessor.cancel(false)
    sessionInitialized.cancel(false)
    val result = Try(whenDone.get(sessionTimeout.toMillis, TimeUnit.MILLISECONDS))
    serverConnection.cancelable.cancel()
    result
  }


  private class BspSessionClient extends BspClient {
    // task notifications
    override def onBuildShowMessage(params: bsp4j.ShowMessageParams): Unit = {
      val event = ShowMessage(params)
      currentJob.notification(event)
      notifications(event)
    }
    override def onBuildLogMessage(params: bsp4j.LogMessageParams): Unit = {
      val event = LogMessage(params)
      currentJob.notification(event)
      notifications(event)
    }
    override def onBuildPublishDiagnostics(params: bsp4j.PublishDiagnosticsParams): Unit = {
      val event = PublishDiagnostics(params)
      currentJob.notification(event)
      notifications(event)
    }

    override def onBuildTaskStart(params: bsp4j.TaskStartParams): Unit = {
      val event = TaskStart(params)
      currentJob.notification(event)
    }

    override def onBuildTaskProgress(params: bsp4j.TaskProgressParams): Unit = {
      val event = TaskProgress(params)
      currentJob.notification(event)
    }

    override def onBuildTaskFinish(params: bsp4j.TaskFinishParams): Unit = {
      val event = TaskFinish(params)
      currentJob.notification(event)
    }

    // build-level notifications
    override def onConnectWithServer(server: bsp4j.BuildServer): Unit = super.onConnectWithServer(server)

    override def onBuildTargetDidChange(didChange: bsp4j.DidChangeBuildTarget): Unit = {
      val event = DidChangeBuildTarget(didChange)
      notifications(event)
    }

  }

  private class BspProcessMessageHandler(input: InputStream) extends Callable[Unit] {

    override def call(): Unit = {
      val lines = Source.fromInputStream(input).getLines()
      lines.foreach { message =>
        lastProcessOutput = System.currentTimeMillis()
        currentJob.log(message + '\n')
      }
    }
  }
}

object BspSession {

  type ProcessLogger = String => Unit
  type NotificationAggregator[A] = (A, BspNotification) => A
  type NotificationCallback = BspNotification => Unit
  type BspSessionTask[T] = BspServer => CompletableFuture[T]

  trait BspServer extends bsp4j.BuildServer with bsp4j.ScalaBuildServer
  trait BspClient extends bsp4j.BuildClient

  private[protocol] def createJob[T,A](
    task: BspSessionTask[T],
    default: A,
    aggregator: NotificationAggregator[A],
    processLogger: ProcessLogger): BspSessionJob[T,A] = {

    new Bsp4jJob(task, default, aggregator, processLogger)
  }

  private[protocol] def builder(
    bspIn: InputStream,
    bspErr: InputStream,
    bspOut: OutputStream,
    initializeBuildParams: bsp4j.InitializeBuildParams,
    cleanup: ()=>Unit): BspSessionBuilder = {

    new BspSessionBuilder(bspIn, bspErr, bspOut, initializeBuildParams, cleanup)
  }

  class BspSessionBuilder private[BspSession](
     bspIn: InputStream,
     bspErr: InputStream,
     bspOut: OutputStream,
     initializeBuildParams: bsp4j.InitializeBuildParams,
     cleanup: ()=>Unit) {

    private var notificationCallbacks: List[NotificationCallback] = Nil
    private var initialJob: BspSessionJob[_,_] = DummyJob

    def addNotificationCallback(callback: NotificationCallback): BspSessionBuilder = {
      notificationCallbacks ::= callback
      this
    }

    def withInitialJob(job: BspSessionJob[_,_]): BspSessionBuilder = {
      initialJob = job
      this
    }

    def create = new BspSession(
      bspIn,
      bspErr,
      bspOut,
      initializeBuildParams,
      cleanup,
      notificationCallbacks,
      initialJob
    )
  }

  private[protocol] abstract class BspSessionJob[T,A] extends BspJob[(T,A)] {

    /** Log message to job--specific logging function. */
    private[protocol] def log(message: String): Unit

    /** Invoke absp notification aggregator or callback with given notification.
      * The result of aggregated notifications is the result value of the future returned by `run`.
      */
    private[BspSession] def notification(bspNotification: BspNotification): Unit

    /** Starts the job with given bspServer instance.
      * Idempotent: returns the same CompletableFuture if invoked more than once.
      */
    private[BspSession] def run(bspServer: BspServer): CompletableFuture[(T, A)]

    /** Cancel and abort this job with given error. */
    private[BspSession] def cancelWithError(error: BspError)
  }

  private class FailedBspSessionJob[T,A](problem: BspError) extends BspSessionJob[T,A] {
    override private[protocol] def log(message: String): Unit = ()
    override private[BspSession] def notification(bspNotification: BspNotification): Unit = ()
    override private[BspSession] def run(bspServer: BspServer): CompletableFuture[(T, A)] = {
      val cf = new CompletableFuture[(T, A)]()
      cf.completeExceptionally(problem)
      cf
    }
    override private[BspSession] def cancelWithError(error: BspError): Unit = ()
    override def future: Future[(T, A)] = Future.failed(problem)
    override def cancel(): Unit = ()

  }

  private class Bsp4jJob[T,A](task: BspSessionTask[T],
                              default: A,
                              aggregator: NotificationAggregator[A],
                              processLogger: ProcessLogger)
    extends BspSessionJob[T,A] {

    private val promise = Promise[(T,A)]
    private var a: A = default

    private var runningTask: Option[CompletableFuture[(T,A)]] = None

    override private[BspSession] def notification(bspNotification: BspNotification): Unit = {
      a = aggregator(a, bspNotification)
    }

    override private[protocol] def log(message: String): Unit = {
      processLogger(message)
    }


    private def doRun(bspServer: BspServer): CompletableFuture[(T,A)] = {
      task(bspServer).thenApply[(T,A)]((t:T) => (t,a))
        .whenComplete((result: (T,A), error: Throwable) => {
          if (error != null) error match {
            case cancel: CancellationException =>
              promise.failure(BspTaskCancelled)
              throw BspTaskCancelled
            case otherError => promise.failure(otherError)
          } else {
            promise.success(result)
          }
        })
    }

    private[BspSession] def run(bspServer: BspServer): CompletableFuture[(T, A)] = runningTask.synchronized {
      runningTask match {
        case Some(running) =>
          running
        case None =>
          val running = doRun(bspServer)
          runningTask = Some(running)
          running
      }
    }

    override def future: Future[(T, A)] = promise.future

    override def cancel() : Unit =
      if (! promise.isCompleted)
        cancelWithError(BspTaskCancelled)

    override def cancelWithError(error: BspError): Unit = runningTask.synchronized {
      runningTask match {
        case Some(toCancel) =>
          toCancel.cancel(true)
        case None =>
          val errorFuture = new CompletableFuture[(T,A)]
          errorFuture.completeExceptionally(error)
          runningTask = Some(errorFuture)
      }

      promise.failure(error)
    }
  }

  private object DummyJob extends BspSessionJob[Unit,Unit] {
    override private[protocol] def log(message: String): Unit = ()
    override private[BspSession] def notification(bspNotification: BspNotification): Unit = ()
    override private[BspSession] def run(bspServer: BspServer): CompletableFuture[(Unit, Unit)] = CompletableFuture.completedFuture(((),()))
    override private[BspSession] def cancelWithError(error: BspError): Unit = ()
    override def future: Future[(Unit,Unit)] = Future.successful(((),()))
    override def cancel(): Unit = ()
  }

  private case class ServerConnection(server: BspServer, cancelable: Cancelable)

  private trait Cancelable {
    def cancel(): Unit
  }

  private class OpenCancelable extends Cancelable {
    private val toCancel = ListBuffer.empty[Cancelable]
    def add(cancelable: Cancelable): Unit = toCancel += cancelable
    override def cancel(): Unit = Cancelable.cancelAll(toCancel)
  }

  private object Cancelable {
    def apply(fn: () => Unit): Cancelable = () => fn()
    val empty: Cancelable = Cancelable(() => ())
    def cancelAll(iterable: Iterable[Cancelable]): Unit = {
      var errors = ListBuffer.empty[Throwable]
      iterable.foreach { cancelable =>
        try cancelable.cancel()
        catch { case NonFatal(ex) => errors += ex }
      }
      errors.toList match {
        case head :: tail =>
          tail.foreach { e =>
            if (e ne head) {head.addSuppressed(e)}
          }
          throw head
        case _ =>
      }
    }
  }

}

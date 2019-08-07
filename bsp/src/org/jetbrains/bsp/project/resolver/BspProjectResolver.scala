package org.jetbrains.bsp.project.resolver

import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture

import ch.epfl.scala.bsp4j._
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project._
import com.intellij.openapi.externalSystem.model.task.{ExternalSystemTaskId, ExternalSystemTaskNotificationEvent, ExternalSystemTaskNotificationListener}
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import org.jetbrains.bsp.BspUtil._
import org.jetbrains.bsp.project.resolver.BspProjectResolver._
import org.jetbrains.bsp.project.resolver.BspResolverDescriptors._
import org.jetbrains.bsp.project.resolver.BspResolverLogic._
import org.jetbrains.bsp.protocol.session.BspSession.{BspServer, NotificationCallback, ProcessLogger}
import org.jetbrains.bsp.protocol.{BspCommunication, BspJob, BspNotifications}
import org.jetbrains.bsp.settings.BspExecutionSettings
import org.jetbrains.bsp.{BspError, BspTaskCancelled}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, TimeoutException}
import scala.util.{Failure, Success, Try}

class BspProjectResolver extends ExternalSystemProjectResolver[BspExecutionSettings] {

  private var importState: ImportState = Inactive

  override def resolveProjectInfo(id: ExternalSystemTaskId,
                                  projectRootPath: String,
                                  isPreviewMode: Boolean,
                                  executionSettings: BspExecutionSettings,
                                  listener: ExternalSystemTaskNotificationListener): DataNode[ProjectData] = {

    val moduleFilesDirectoryPath = new File(projectRootPath, ".idea/modules").getAbsolutePath

    def statusUpdate(msg: String): Unit = {
      val ev = new ExternalSystemTaskNotificationEvent(id, msg)
      listener.onStatusChange(ev)
    }

    def requests(implicit server: BspServer): CompletableFuture[Try[DataNode[ProjectData]]] = {
      val targetsRequest = server.workspaceBuildTargets()

      val projectNodeFuture: CompletableFuture[Try[DataNode[ProjectData]]] =
        targetsRequest.thenCompose { targetsResponse =>
          val targets = targetsResponse.getTargets.asScala
          val targetIds = targets.map(_.getId).toList
          val td = targetData(targetIds, isPreviewMode)
          td.thenApply { data =>
            for {
              sources <- data.sources
              depSources <- data.dependencySources // TODO not required for project, should be warning
              resources <- data.resources
              scalacOptions <- data.scalacOptions // TODO not required for non-scala modules
            } yield {
              val descriptions = calculateModuleDescriptions(
                targets,
                scalacOptions.getItems.asScala,
                sources.getItems.asScala,
                resources.getItems.asScala,
                depSources.getItems.asScala
              )
              projectNode(projectRootPath, moduleFilesDirectoryPath, descriptions)
            }
          }
        }

      projectNodeFuture
    }

    // TODO reuse existing connection if available. https://youtrack.jetbrains.com/issue/SCL-14847
    val communication = BspCommunication.forBaseDir(projectRootPath, executionSettings)

    val notifications: NotificationCallback = {
      case BspNotifications.LogMessage(params) =>
        // TODO use params.id for tree structure
        statusUpdate(params.getMessage)
      case _ =>
    }

    val processLogger: ProcessLogger = { msg =>
      listener.onTaskOutput(id, msg, true)
    }

    val projectJob =
      communication.run(requests(_), notifications, processLogger)

    listener.onStart(id, projectRootPath)
    statusUpdate("BSP import started") // TODO remove in favor of build toolwindow nodes

    importState = Active(communication)
    val result = waitForProjectCancelable(projectJob)
    communication.closeSession()
    importState = Inactive

    statusUpdate("BSP import completed") // TODO remove in favor of build toolwindow nodes

    result match {
      case Failure(BspTaskCancelled) =>
        listener.onCancel(id)
        null
      case Failure(err: Exception) =>
        listener.onFailure(id, err)
        throw err
      case Success(data) =>
        listener.onSuccess(id)
        data
    }
  }

  @tailrec private def waitForProjectCancelable[T](projectJob: BspJob[Try[DataNode[ProjectData]]]): Try[DataNode[ProjectData]] =
    importState match {
      case Active(_) =>
        try { Await.result(projectJob.future, 300.millis) }
        catch {
          case _: TimeoutException => waitForProjectCancelable(projectJob)
        }
      case Inactive =>
        projectJob.cancel()
        Failure(BspTaskCancelled)
    }

  override def cancelTask(taskId: ExternalSystemTaskId,
                          listener: ExternalSystemTaskNotificationListener): Boolean =
    importState match {
      case Active(session) =>
        listener.beforeCancel(taskId)
        importState = Inactive
        session.closeSession()
        listener.onCancel(taskId)
        true
      case Inactive =>
        false
    }

}

object BspProjectResolver {

  private sealed abstract class ImportState
  private case class Active(communication: BspCommunication) extends ImportState
  private case object Inactive extends ImportState

  private[resolver] def targetData(targetIds: List[BuildTargetIdentifier], isPreview: Boolean)(implicit bsp: BspServer):
  CompletableFuture[TargetData] =
    if (isPreview) {
      val emptySources = Success(new SourcesResult(Collections.emptyList()))
      val emptyResources = Success(new ResourcesResult(Collections.emptyList()))
      val emptyDepSources = Success(new DependencySourcesResult(Collections.emptyList()))
      val emptyScalacOpts = Success(new ScalacOptionsResult(Collections.emptyList()))
      CompletableFuture.completedFuture(TargetData(emptySources, emptyDepSources, emptyResources, emptyScalacOpts))
    } else {
      val targets = targetIds.asJava

      val sourcesParams = new SourcesParams(targets)
      val sources = bsp.buildTargetSources(sourcesParams).catchBspErrors

      val depSourcesParams = new DependencySourcesParams(targets)
      val depSources = bsp.buildTargetDependencySources(depSourcesParams).catchBspErrors

      val resourcesParams = new ResourcesParams(targets)
      val resources = bsp.buildTargetResources(resourcesParams).catchBspErrors

      val scalacOptionsParams = new ScalacOptionsParams(targets)
      val scalacOptions = bsp.buildTargetScalacOptions(scalacOptionsParams).catchBspErrors

      CompletableFuture
        .allOf(sources, depSources, resources, scalacOptions)
        .thenApply(_ => TargetData(sources.get, depSources.get, resources.get, scalacOptions.get))
    }
}

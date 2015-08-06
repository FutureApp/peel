package eu.stratosphere.peel.extensions.spark.results.etl

import java.io.File
import java.time.Instant

import akka.actor.{ActorRef, Props}
import eu.stratosphere.peel.core.results.etl.extractor.{EventExtractor, EventExtractorCompanion, PatternBasedProcessMatching}
import eu.stratosphere.peel.core.results.etl.reader.{FileReader, JsonFileReader, Line}
import eu.stratosphere.peel.core.results.model.{ExperimentEvent, ExperimentRun}
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.matching.Regex

/** An extractor for Spark task transition events. */
class SparkTaskEventExtractor(
  override val run: ExperimentRun,
  override val appContext: ApplicationContext,
  override val writer: ActorRef) extends EventExtractor[Line] {

  import TaskEventProtocol.format

  /** Extracts events from an incoming message */
  final def receive: Receive = {
    case msg@JsObject(fields) =>
      for {
        eventType            <- fields.get("Event") if JsString("SparkListenerTaskEnd") == eventType
        JsNumber(stageID)    <- fields.get("Stage ID")
        JsNumber(stageAttID) <- fields.get("Stage Attempt ID")
        taskInfo             <- Option(fields.get("Task Info").toJson.convertTo[TaskInfo]) if !taskInfo.failed
      } {
        // create event prototype
        val eventPrototype = ExperimentEvent(
          experimentRunID = run.id,
          name            = 'prototype,
          task            = Some(s"stage-$stageID#$stageAttID"),
          taskInstance    = Some(taskInfo.taskId),
          vLong           = Some(taskInfo.index))
        // send state_change_running event
        writer ! eventPrototype.copy(
          name            = 'state_change_running,
          vTimestamp      = Some(Instant.ofEpochSecond(taskInfo.launchTime)))
        // send state_change_finished event
        writer ! eventPrototype.copy(
          name            = 'state_change_finished,
          vTimestamp      = Some(Instant.ofEpochSecond(taskInfo.finishTime)))
      }
  }
}

/** Companion object. */
@Component
object SparkTaskEventExtractor extends EventExtractorCompanion with PatternBasedProcessMatching {

  /** A list of file patterns for in which the event extractor is interested */
  override val filePatterns: Seq[Regex] = {
    Seq("""app-\d+-\d+""".r)
  }

  /** Constructs a reader that parses the file as a sequence of objects that can be handled by the extractor actor. */
  override def reader(file: File): FileReader[Any] = {
    JsonFileReader(file)
  }

  /** Create the extractor props. */
  override def props(run: ExperimentRun, context: ApplicationContext, writer: ActorRef): Props = {
    Props(new SparkTaskEventExtractor(run, context, writer))
  }
}

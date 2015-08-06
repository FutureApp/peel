package eu.stratosphere.peel.extensions.flink.results.etl

import java.io.File

import akka.actor.{ActorRef, Props}
import eu.stratosphere.peel.core.results.etl.extractor.{EventExtractor, EventExtractorCompanion, PatternBasedProcessMatching}
import eu.stratosphere.peel.core.results.etl.reader.{FileReader, Line, LineFileReader}
import eu.stratosphere.peel.core.results.model.{ExperimentEvent, ExperimentRun}
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

import scala.util.matching.Regex

/** An extractor for Flink task transition events. */
class FlinkTaskEventExtractor(
  override val run: ExperimentRun,
  override val appContext: ApplicationContext,
  override val writer: ActorRef) extends EventExtractor[Line] {

  /** Extracts events from an incoming message */
  final def receive: Receive = {
    case msg@Line(LogEntry(time, TaskState(name, number, total, state))) =>
      writer ! ExperimentEvent(
        run.id,
        Symbol(s"state_change_${state.toLowerCase}"),
        Some(name),
        Some(number.toInt),
        vTimestamp = Some(toInstant(time)))
  }
}

/** Companion object. */
@Component
object FlinkTaskEventExtractor extends EventExtractorCompanion with PatternBasedProcessMatching {

  /** A list of file patterns for in which the event extractor is interested */
  override val filePatterns: Seq[Regex] = {
    Seq("run.out".r)
  }

  /** Constructs a reader that parses the file as a sequence of objects that can be handled by the extractor actor. */
  override def reader(file: File): FileReader[Any] = {
    LineFileReader(file)
  }

  /** Create the extractor props. */
  override def props(run: ExperimentRun, context: ApplicationContext, writer: ActorRef): Props = {
    Props(new FlinkTaskEventExtractor(run, context, writer))
  }
}

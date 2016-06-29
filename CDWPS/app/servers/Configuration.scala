package servers

import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.ExecutionException

import nasa.nccs.cdapi.kernels.{BlockingExecutionResult, ErrorExecutionResult, ExecutionResults, XmlExecutionResult}
import nasa.nccs.esgf.engine.demoExecutionManager
import nasa.nccs.utilities.cdsutils
import org.slf4j.LoggerFactory
import play.api.Play
import utilities.parsers.wpsObjectParser.cdata

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

abstract class ServiceProvider {
  val logger = LoggerFactory.getLogger(this.getClass)

  def executeProcess(identifier: String, parsed_data_inputs: Map[String, Seq[Map[String, Any]]], runargs: Map[String, String]): xml.Elem

  def listProcesses(): xml.Elem

  def describeProcess( identifier: String ): xml.Elem

  def getCause( e: Throwable ): Throwable = e match {
    case err: ExecutionException => err.getCause; case x => e
  }

  def getResultFilePath( resultId: String ): Option[String]

  def fatal( e: Throwable ): xml.Elem = {
    val err = getCause( e )
    logger.error( "\nError Executing Kernel: %s\n".format(err.getMessage) )
    val sw = new StringWriter
    err.printStackTrace(new PrintWriter(sw))
    logger.error( sw.toString )
    <error id="Execution Error"> { err.getMessage } </error>
  }

}

object esgfServiceProvider extends ServiceProvider {
  import nasa.nccs.esgf.engine.demoExecutionManager

  override def executeProcess(process_name: String, datainputs: Map[String, Seq[Map[String, Any]]], runargs: Map[String, String]): xml.Elem = {
    try { demoExecutionManager.execute(process_name, datainputs, runargs) } catch { case e: Exception => <error id="Execution Error"> {e.getMessage} </error> }
  }
  override def describeProcess(process_name: String): xml.Elem = {
    try {  demoExecutionManager.describeProcess(process_name) } catch {  case e: Exception => <error id="Execution Error"> {e.getMessage} </error> }
  }
  override def listProcesses(): xml.Elem = {
    try {  demoExecutionManager.listProcesses() } catch {  case e: Exception => <error id="Execution Error"> {e.getMessage} </error> }
  }
  override def getResultFilePath( resultId: String ): Option[String] = None
}

object cds2ServiceProvider extends ServiceProvider {
  import nasa.nccs.cds2.engine.CDS2ExecutionManager
  import nasa.nccs.esgf.process.TaskRequest

  val cds2ExecutionManager = new CDS2ExecutionManager( serverConfiguration )

  def serverConfiguration: Map[String,String] = {
    try {
      val config = Play.current.configuration
      def get_config_value(key: String): Option[String] = {
        try {
          config.getString(key)
        } catch {
          case ex: Exception => None
        }
      }
      val map_pairs = for (key <- config.keys; value = get_config_value(key); if value.isDefined) yield (key -> value.get.toString)
      Map[String, String](map_pairs.toSeq: _*)
    } catch {
      case e: Exception => Map[String, String](  "wps.results.dir" -> "~/.wps/results" )
    }
  }
  override def executeProcess(process_name: String, datainputs: Map[String, Seq[Map[String, Any]]], runargs: Map[String, String]): xml.Elem = {
    try {
      cdsutils.time( logger, "-->> Process %s: Total Execution Time: ".format(process_name) ) {
        if( runargs.getOrElse("async","false").toBoolean ) {
          cds2ExecutionManager.executeAsync(TaskRequest(process_name, datainputs), runargs) match {
            case ( resultId: String, futureResult: Future[ExecutionResults] ) => <result url={"http://server:port/wps/results?id=%s".format(resultId)}> {resultId} </result>
            case x =>  <error id="Execution Error"> {"Malformed response from cds2ExecutionManager" } </error>
          }
        }
        else  cds2ExecutionManager.blockingExecute(TaskRequest(process_name, datainputs), runargs).toXml
      }
    } catch { case e: Exception => fatal(e) }
  }
  override def describeProcess(process_name: String): xml.Elem = {
    try {
      cds2ExecutionManager.describeProcess( process_name )

    } catch { case e: Exception => fatal(e) }
  }
  override def listProcesses(): xml.Elem = {
    try {
      cds2ExecutionManager.listProcesses()

    } catch { case e: Exception => fatal(e) }
  }
  override def getResultFilePath( resultId: String ): Option[String] = cds2ExecutionManager.getResultFilePath( resultId )
}

object demoServiceProvider extends ServiceProvider {

  override def executeProcess(identifier: String, parsed_data_inputs: Map[String, Seq[Map[String, Any]]], runargs: Map[String, String]): xml.Elem = {
    <result id={ identifier }>
      <inputs>  { cdata(parsed_data_inputs) } </inputs>
      <runargs> { cdata(runargs) } </runargs>
    </result>
  }
  override def describeProcess(identifier: String): xml.Elem = {
    <process id={ identifier }></process>
  }
  override def listProcesses(): xml.Elem = {
    <processes></processes>
  }
  override def getResultFilePath( resultId: String ): Option[String] = None
}

object ServiceProviderConfiguration {

  val providers = Seq(
    "esgf" -> esgfServiceProvider,
    "cds2" -> cds2ServiceProvider,
    "demo" -> demoServiceProvider,
    "test" -> esgfServiceProvider
  )

  val default_service = "esgf"

}

object resourceTest extends App {
  import nasa.nccs.cds2.engine.CDS2ExecutionManager
  val serverConfiguration: Map[String,String] = Map()

  val cds2ExecutionManager = new CDS2ExecutionManager( serverConfiguration )

  val resourcePath = cds2ExecutionManager.getResourcePath("/collections.xml")
  println( resourcePath )
}

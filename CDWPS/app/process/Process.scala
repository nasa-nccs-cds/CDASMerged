package process

import play.api.Play

import scala.collection.mutable
import scala.collection.immutable
import scala.xml._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import process.exceptions._
import servers.APIManager

/*class ProcessInput(val name: String, val itype: String, val maxoccurs: Int, val minoccurs: Int) {

  def toXml = {
    <input id={ name } type={ itype } maxoccurs={ maxoccurs.toString } minoccurs={ minoccurs.toString }/>
  }
}

class Process(val name: String, val description: String, val inputs: List[ProcessInput]) {

  def toXml =
    <process id={ name }>
      <description id={ description }> </description>
      <inputs>
        { inputs.map(_.toXml ) }
      </inputs>
    </process>

  def toXmlHeader =
    <process id={ name }> <description> { description } </description> </process>
}

class ProcessList(val process_list: List[Process]) {

  def toXml =
    <processes>
      { process_list.map(_.toXml ) }
    </processes>

  def toXmlHeaders =
    <processes>
      { process_list.map(_.toXmlHeader ) }
    </processes>
}*/

class ProcessManager() {
  val logger = LoggerFactory.getLogger(this.getClass)
  def apiManager = APIManager( )

  def printLoggerInfo = {
    import ch.qos.logback.classic.LoggerContext
    import ch.qos.logback.core.util.StatusPrinter
    StatusPrinter.print( LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext] )
  }

  def unacceptable(msg: String): Unit = {
    logger.error(msg)
    throw new NotAcceptableException(msg)
  }

  def describeProcess(service: String, name: String): xml.Elem = {
    apiManager.getServiceProvider(service) match {
      case Some(serviceProvider) =>
        logger.info("Executing Service %s, Service provider = %s ".format( service, serviceProvider.getClass.getName ))
        serviceProvider.describeProcess( name )
      case None =>
        throw new NotAcceptableException("Unrecognized service: " + service)
    }
  }

  def listProcesses(service: String): xml.Elem = {
    apiManager.getServiceProvider(service) match {
      case Some(serviceProvider) =>
        logger.info("Executing Service %s, Service provider = %s ".format( service, serviceProvider.getClass.getName ))
        serviceProvider.listProcesses()
      case None =>
        throw new NotAcceptableException("Unrecognized service: " + service)
    }
  }

  def executeProcess(service: String, process_name: String, datainputs: Map[String, Seq[Map[String, Any]]], runargs: Map[String, String]): xml.Elem = {
    apiManager.getServiceProvider(service) match {
      case Some(serviceProvider) =>
        logger.info("Executing Service %s, Service provider = %s ".format( service, serviceProvider.getClass.getName ))
        serviceProvider.executeProcess(process_name, datainputs, runargs)
      case None => throw new NotAcceptableException("Unrecognized service: " + service)
    }
  }

  def getResultFilePath( service: String, resultId: String ): Option[String] = {
    apiManager.getServiceProvider(service) match {
      case Some(serviceProvider) => serviceProvider.getResultFilePath(resultId)
      case None => throw new NotAcceptableException("Unrecognized service: " + service)
    }
  }
}

object webProcessManager extends ProcessManager() {}


/*
import org.scalatest.FunSuite

class ParserTest extends FunSuite {

  test("DescribeProcess") {
    println(webProcessManager.listProcesses())
    val process = webProcessManager.describeProcess("CWT.Sum")
    process match {
      case Some(p) => println(p)
      case None => println("Unrecognized process")
    }
    assert(true)
  }

}
*/


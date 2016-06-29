package controllers

import org.slf4j.LoggerFactory
import play.api._
import java.io.File
import play.api.Play
import play.api.mvc._
import process.webProcessManager
import process.exceptions._
import utilities.parsers.{CDSecurity, wpsObjectParser, BadRequestException}

class Application extends Controller {

  def index = Action {
    Ok(views.html.index("Your application is ready."))
  }
  def test = Action {
    Ok("Hello").withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
  }
}

class WPS extends Controller {
  val logger = LoggerFactory.getLogger(this.getClass)

  def demo = Action {
    Ok(views.html.demo())
  }

  def getResult( id: String, service: String ) = Action {
    try{
      webProcessManager.getResultFilePath( service, id )  match {
        case Some( resultFilePath: String ) =>
          logger.info(s"WPS getResult: resultFilePath=$resultFilePath")
          Ok.sendFile ( new File(resultFilePath) ).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
        case None => NotFound("Result not yet available").withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
      }
    } catch { case e: Exception => InternalServerError( e.getMessage ).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" ) }
  }

  def execute(version: String,
              request: String,
              identifier: String,
              service: String,
              responseform: String,
              storeexecuteresponse: Boolean,
              status: Boolean,
              datainputs: String) = Action {
    try {
      request.toLowerCase match {
        case "getcapabilities" =>
          Ok(webProcessManager.listProcesses(service)).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
        case "describeprocess" =>
          Ok(webProcessManager.describeProcess(service, identifier)).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
        case "execute" =>
          val t0 = System.nanoTime()
          val runargs = Map("responseform" -> responseform.toString, "storeexecuteresponse" -> storeexecuteresponse.toString, "async" -> status.toString)
          logger.info(s"WPS EXECUTE: identifier=$identifier, service=$service, runargs=$runargs, datainputs=$datainputs")
          val parsed_data_inputs = wpsObjectParser.parseDataInputs(datainputs)
          val response: xml.Elem = webProcessManager.executeProcess(service, identifier, parsed_data_inputs, runargs)
          logger.info( "Completed request '%s' in %.4f sec".format( identifier, (System.nanoTime()-t0)/1.0E9) )
//          val printer = new scala.xml.PrettyPrinter(200, 3)
//          println("---------->>>> Final Result: " + printer.format(response))
          Ok(response).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
      }
    } catch {
      case e: BadRequestException => BadRequest(<error type="ImproperlyFormedRequest"> {"<![CDATA[\n " + CDSecurity.sanitize( e.getMessage ) + "\n]]>"} </error>).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
      case e: NotAcceptableException => NotAcceptable(<error type="UnacceptableRequest"> {"<![CDATA[\n " + CDSecurity.sanitize( e.getMessage ) + "\n]]>"} </error>).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
      case e: Exception => InternalServerError(<error type="InternalServerError"> {"<![CDATA[\n " + CDSecurity.sanitize( e.getMessage ) + "\n]]>"} </error>).withHeaders( ACCESS_CONTROL_ALLOW_ORIGIN -> "*" )
    }
  }
}

object executeTest extends App {
  val identifier="CDS.workflow"
  val service="cds2"
  val runargs : Map[String,String] =Map( "responseform" -> "", "storeexecuteresponse" -> "true", "async" -> "false")
  val datainputs = """[domain=[{"name":"r0","longitude":{"start":-124.925,"end":-124.925,"system":"values"},"latitude":{"start":-7.0854263305664205,"end":-7.0854263305664205,"system":"values"},"level":{"start":100000,"end":100000,"system":"values"}},{"name":"r1","time":{"start":"2010-01-16T12:00:00","end":"2010-01-16T12:00:00","system":"values"}}],variable={"uri":"collection://MERRA/mon/atmos","name":"ta:v0","domain":"r0"},operation=["CDS.anomaly(v0,axes:t)","CDS.bin(v0,axes:t,bins:t|month|ave|year)","CDS.subset(v0,domain:r1)"]]"""
  val parsed_data_inputs = wpsObjectParser.parseDataInputs( datainputs )
  val response: xml.Elem = webProcessManager.executeProcess( service, identifier, parsed_data_inputs, runargs )
  for( node <- response.child ) {
    println( node.label + " : " +  node.attributes.mkString(",") )
  }
}

object parseTest  extends App {
  val datainputs = """ [domain=[{"name":"d0","lat":{"start":45,"end":45,"system":"values"},"lon":{"start":30,"end":30,"system":"values"},"lev":{"start":3,"end":3,"system":"indices"}}],variable={"uri":"collection://MERRA/mon/atmos","name":"ta:v0","domain":"d0"},operation=[{ "input":["v0"], "unit":"month", "period":"12" }]  ] """
  val parsed_data_inputs = wpsObjectParser.parseDataInputs(datainputs)
  println( parsed_data_inputs )
}



package nasa.nccs.cds2.loaders
import java.io.{FileNotFoundException, FileOutputStream}
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.{Files, Path, Paths}

import collection.JavaConverters._
import scala.collection.JavaConversions._
import collection.mutable
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import nasa.nccs.cdapi.cdm.{Collection, NCMLWriter}
import nasa.nccs.utilities.Loggable

import scala.concurrent.Future
import scala.xml.XML

object AxisNames {
  def apply( x: String = "", y: String = "", z: String = "", t: String = "" ): Option[AxisNames] = {
    val nameMap = Map( 'x' -> x, 'y' -> y, 'z' -> z, 't' -> t )
    Some( new AxisNames( nameMap ) )
  }
}
class AxisNames( val nameMap: Map[Char,String]  ) {
  def apply( dimension: Char  ): Option[String] = nameMap.get( dimension ) match {
    case Some(name) => if (name.isEmpty) None else Some(name)
    case None=> throw new Exception( s"Not an axis: $dimension" )
  }
}

trait XmlResource extends Loggable {
  val Encoding = "UTF-8"

  def saveXML( fileName: String, node: xml.Node ) = {
    val pp = new xml.PrettyPrinter( 800, 2 )
    val fos = new FileOutputStream(fileName)
    val writer = Channels.newWriter(fos.getChannel(), Encoding)
    try {
      writer.write("<?xml version='1.0' encoding='" + Encoding + "'?>\n")
      writer.write(pp.format(node))
    } finally {
      writer.close()
    }
  }
  def getFilePath(resourcePath: String) = Option( getClass.getResource(resourcePath) ) match {
      case None => Option( getClass.getClassLoader.getResource(resourcePath) ) match {
        case None => throw new Exception(s"Resource $resourcePath does not exist!")
        case Some(r) => r.getPath
      }
      case Some(r) => r.getPath
    }

  def attr( node: xml.Node, att_name: String ): String = { node.attribute(att_name) match { case None => ""; case Some(x) => x.toString }}
  def attrOpt( node: xml.Node, att_name: String ): Option[String] = node.attribute(att_name).map( _.toString )
  def normalize(sval: String): String = sval.stripPrefix("\"").stripSuffix("\"").toLowerCase
  def nospace( value: String ): String  = value.filter(_!=' ')
}

object Mask  {
  def apply( mtype: String, resource: String ) = { new Mask(mtype,resource) }
}
class Mask( val mtype: String, val resource: String ) extends XmlResource {
  override def toString = "Mask( mtype=%s, resource=%s )".format( mtype, resource )
  def getPath: String = getFilePath( resource )
}

object Masks extends XmlResource {
  val mid_prefix: Char = '#'
  val masks = loadMaskXmlData(getFilePath("/masks.xml"))

  def isMaskId( maskId: String ): Boolean = (maskId(0) == mid_prefix )

  def loadMaskXmlData(filePath:String): Map[String,Mask] = {
    Map(XML.loadFile(filePath).child.flatMap( node => node.attribute("id") match {
      case None => None;
      case Some(id) => Some( (mid_prefix +: id.toString) -> createMask(node)); }
    ) :_* )
  }
  def createMask( n: xml.Node ): Mask = { Mask( attr(n,"mtype"), attr(n,"resource") ) }

  def getMask( id: String ): Option[Mask] = masks.get(id)

  def getMaskIds: Set[String] = masks.keySet
}

object Collections extends XmlResource {
  val maxCapacity: Int=100000
  val initialCapacity: Int=250
  val datasets: ConcurrentLinkedHashMap[String,Collection] =  loadCollectionXmlData( Map( "global" -> getFilePath("/global_collections.xml"), "local" -> getFilePath("/local_collections.xml") ) )

  def toXml: xml.Elem = {
    <collections>
      { for( ( id: String, collection:Collection ) <- datasets ) yield collection.toXml }
    </collections>
  }

  def idSet: Set[String] = datasets.keySet.toSet

  def toXml( scope: String ): xml.Elem = {
    <collections>
      { for( ( id: String, collection:Collection ) <- datasets; if collection.scope.equalsIgnoreCase(scope) ) yield collection.toXml }
    </collections>
  }

  def uriToFile( uri: String ): String = {
    uri.toLowerCase.split(":").last.stripPrefix("/").stripPrefix("/").replaceAll("[-/]","_").replaceAll("[^a-zA-Z0-9_.]", "X") + ".xml"
  }

  def addCollection( uri: String, path: String, fileFilter: String, vars: List[String]  ): Collection = {
    val prettyPrint = true
    val url = "file:" + NCMLWriter.getCachePath("NCML").resolve( uriToFile(uri) )
    val id = uri.split(":").last.stripPrefix("/").stripPrefix("/").toLowerCase
    val collection = Collection( id, url, path, fileFilter, "local", vars )
    datasets.put( uri, collection  )
    if(prettyPrint) saveXML( getFilePath("/local_collections.xml"), toXml("local") )
    else XML.save( getFilePath("/local_collections.xml"), toXml("local") )
    collection
  }

//  def loadCollectionTextData(url:URL): Map[String,Collection] = {
//    val lines = scala.io.Source.fromURL( url ).getLines
//    val mapItems = for( line <- lines; toks =  line.split(';')) yield
//      nospace(toks(0)) -> Collection( url=nospace(toks(1)), url=nospace(toks(1)), vars=getVarList(toks(3)) )
//    mapItems.toMap
//  }

  def isChild( subDir: String,  parentDir: String ): Boolean = Paths.get( subDir ).toAbsolutePath.startsWith( Paths.get( parentDir ).toAbsolutePath )
  def findCollectionByPath( subDir: String ): Option[Collection] = datasets.values.toList.find { case collection => if( collection.path.isEmpty) { false } else { isChild( subDir, collection.path ) } }

  def loadCollectionXmlData( filePaths: Map[String,String] ): ConcurrentLinkedHashMap[String,Collection] = {
    val maxCapacity: Int=100000
    val initialCapacity: Int=250
    val datasets = new ConcurrentLinkedHashMap.Builder[String, Collection].initialCapacity(initialCapacity).maximumWeightedCapacity(maxCapacity).build()
    for ( ( scope, filePath ) <- filePaths.iterator; if Files.exists( Paths.get(filePath) ) ) {
      try {
        XML.loadFile(filePath).child.foreach( node => node.attribute("id") match {
          case None => None;
          case Some(id) => datasets.put(id.toString.toLowerCase, getCollection(node,scope))
        })
      } catch { case err: java.io.IOException => throw new Exception( "Error opening collection data file {%s}: %s".format( filePath, err.getMessage) ) }
    }
    datasets
  }

  def getVarList( var_list_data: String  ): List[String] = var_list_data.filter(!List(' ','(',')').contains(_)).split(',').toList
  def getCollection( n: xml.Node, scope: String ): Collection = { Collection( attr(n,"id"), attr(n,"url"), attr(n,"path"), attr(n,"fileFilter"), scope, n.text.split(",").toList )}

  def findCollection( collectionId: String ): Option[Collection] = Option( datasets.get( collectionId ) )

  def getCollectionXml( collectionId: String ): xml.Elem = {
    Option( datasets.get( collectionId ) ) match {
      case Some( collection: Collection ) => collection.toXml
      case None => <error> { "Invalid collection id:" + collectionId } </error>
    }
  }
  def parseUri( uri: String ): ( String, String ) = {
    if (uri.isEmpty) ("", "")
    else {
      val uri_parts = uri.split(":/")
      val url_type = normalize(uri_parts.head)
      if(uri_parts.length == 2) (url_type, uri_parts.last)
      else throw new Exception("Unrecognized uri format: " + uri + ", type = " + uri_parts.head + ", nparts = " + uri_parts.length.toString + ", value = " + uri_parts.last)
    }
  }

//  def getCollection(collection_uri: String, var_names: List[String] = List()): Option[Collection] = {
//    parseUri(collection_uri) match {
//      case (ctype, cpath) => ctype match {
//        case "file" => Some(Collection( url = collection_uri, vars = var_names ))
//        case "collection" =>
//          val collection_key = cpath.stripPrefix("/").stripSuffix("\"").toLowerCase
//          logger.info( " getCollection( %s ) ".format(collection_key) )
//          datasets.get( collection_key )
//      }
//    }
//  }

  def getCollectionKeys: Array[String] = datasets.keys.toArray

}


object TestCollection extends App {
  println( Collections.isChild( "/tmp",  "/tmp" ) )
}

object TestMasks extends App {
  println( Masks.masks.toString )
}

object TestReplace extends App {
  val s0 = "/x-one!"
  println(s0.replaceAll("[-/]","_").replaceAll("[^a-zA-Z0-9_.]", "X"))
}








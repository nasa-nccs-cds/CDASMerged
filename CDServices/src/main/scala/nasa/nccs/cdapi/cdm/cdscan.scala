package nasa.nccs.cdapi.cdm

import java.io._
import java.nio.file.{FileSystems, Path, Paths}
import java.util.Formatter

import nasa.nccs.cdapi.tensors.CDDoubleArray
import nasa.nccs.cds2.loaders.Collections
import nasa.nccs.utilities.Loggable
import nasa.nccs.utilities.cdsutils
import ucar.{ma2, nc2}
import ucar.nc2.constants.AxisType
import ucar.nc2.dataset.{CoordinateAxis1D, CoordinateAxis, CoordinateAxis1DTime, NetcdfDataset, VariableDS}
import ucar.nc2.time.CalendarDate
import collection.mutable.ListBuffer

import scala.collection.JavaConversions._
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.xml.XML

object NCMLWriter {

  def apply( path: String ): NCMLWriter = { new NCMLWriter( Array(path).iterator ) }

  def isNcFile( file: File ): Boolean = {
    val fname = file.getName.toLowerCase
    file.isFile && (fname.endsWith(".nc4") || fname.endsWith(".nc") || fname.endsWith(".hdf") )
  }
  def getCacheDir: String = {
    val collection_file_path = Collections.getFilePath("/local_collections.xml")
    new java.io.File( collection_file_path ).getParent.stripSuffix("/")
  }

  def getCachePath( subdir: String ): Path = {  FileSystems.getDefault().getPath( getCacheDir, subdir ) }

  def getNcFiles(file: File): Iterable[File] = {
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    ( Seq(file) ++: children.flatMap(getNcFiles(_)) ).filter( NCMLWriter.isNcFile(_) )
  }

  def getNcFiles(args: Iterator[String]): Iterator[File] =
    args.map( (arg: String) => NCMLWriter.getNcFiles(new File(arg))).foldLeft(Iterator[File]())(_ ++ _)

  def getFileHeaders( files: IndexedSeq[File], nReadProcessors: Int ): IndexedSeq[FileHeader] = {
    val groupSize = cdsutils.ceilDiv( files.length, nReadProcessors )
    val fileGroups = files.grouped(groupSize).toIndexedSeq
    val fileHeaderFuts  = Future.sequence( for( workerIndex <- fileGroups.indices; fileGroup = fileGroups(workerIndex) ) yield Future { FileHeader.factory( fileGroup, workerIndex ) } )
    Await.result( fileHeaderFuts, Duration.Inf ).foldLeft( IndexedSeq[FileHeader]() ) {_ ++ _} sortWith { ( afr0, afr1 ) =>  (afr0.startValue < afr1.startValue) }
  }
}

//class NCMLSerialWriter(val args: Iterator[String]) {
//  val files: IndexedSeq[File] = NCMLWriter.getNcFiles(args).toIndexedSeq
//  val nFiles = files.length
//  val fileHeaders = NCMLWriter.getFileHeadersSerial(files)
//
//  def getNCML: xml.Node = {
//    <netcdf xmlns="http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2">
//      <attribute name="title" type="string" value="NetCDF aggregated dataset"/>
//      <aggregation dimName="time" units="days since 1970-1-1" type="joinExisting">
//        { for( fileHeader <- fileHeaders ) yield { <netcdf location={"file:" + fileHeader.path} ncoords={fileHeader.nElem.toString}> { fileHeader.axisValues.mkString(", ") } </netcdf> } }
//      </aggregation>
//    </netcdf>
//  }
//}

class NCMLWriter(args: Iterator[String], val maxCores: Int = 30) {
  private val nReadProcessors = Math.min(Runtime.getRuntime.availableProcessors - 1, maxCores)
  private val files: IndexedSeq[File] = NCMLWriter.getNcFiles(args).toIndexedSeq
  private val nFiles = files.length
  val fileHeaders = NCMLWriter.getFileHeaders(files, nReadProcessors)
  val fileMetadata = FileMetadata(files.head)
  val outerDimensionSize: Int = fileHeaders.foldLeft(0)(_ + _.nElem)
  val ignored_attributes = List( "comments" )

  def isIgnored( attribute: nc2.Attribute ): Boolean = { ignored_attributes.contains(attribute.getShortName) }

  def getAttribute(attribute: nc2.Attribute): xml.Node =
    if (attribute.getDataType == ma2.DataType.STRING) {
      if (attribute.getLength > 1) {
        val sarray: IndexedSeq[String] = (0 until attribute.getLength).map(i => attribute.getStringValue(i).filter(ch => org.jdom2.Verifier.isXMLCharacter(ch)))
          <attribute name={attribute.getShortName} value={sarray.mkString("|")} separator="|"/>
      } else {
          <attribute name={attribute.getShortName} value={attribute.getStringValue(0)}/>
      }
    } else {
      if (attribute.getLength > 1) {
        val sarray: IndexedSeq[String] = (0 until attribute.getLength).map(i => attribute.getNumericValue(i).toString)
          <attribute name={attribute.getShortName} type={attribute.getDataType.toString} value={sarray.mkString(" ")}/>
      } else {
          <attribute name={attribute.getShortName} type={attribute.getDataType.toString} value={attribute.getNumericValue(0).toString}/>
      }
    }

  def getDims(variable: nc2.Variable): String = variable.getDimensions.map(dim => if (dim.isShared) dim.getShortName else if (dim.isVariableLength) "*" else dim.getLength.toString).toArray.mkString(" ")

  def getDimension(axis: CoordinateAxis ): xml.Node = {
    axis match {
      case coordAxis: CoordinateAxis1D =>
        val nElems = if( coordAxis.getAxisType == AxisType.Time ) outerDimensionSize else coordAxis.getSize
        val dimension = coordAxis.getDimension(0)
          <dimension name={dimension.getFullName} length={nElems.toString} isUnlimited={dimension.isUnlimited.toString} isVariableLength={dimension.isVariableLength.toString} isShared={dimension.isShared.toString}/>
      case _ => throw new Exception( "Multidimensional coord axes not currently supported")
    }
  }

  def getAggDataset(fileHeader: FileHeader, timeRegular: Boolean ): xml.Node =
    if( timeRegular ) <netcdf location={"file:" + fileHeader.path} ncoords={fileHeader.nElem.toString}/>
    else <netcdf location={"file:" + fileHeader.path} ncoords={fileHeader.nElem.toString} coordValue={fileHeader.axisValues.map("%.4f".format(_)).mkString(", ")}/>


  def getVariable( variable: nc2.Variable, timeRegularSpecs: Option[(Double,Double)] ): xml.Node = {
    val axisType = fileMetadata.getAxisType(variable)
    <variable name={variable.getShortName} shape={getDims(variable)} type={variable.getDataType.toString}>
      { if( axisType == AxisType.Time ) <attribute name="_CoordinateAxisType" value="Time"/> else for (attribute <- variable.getAttributes; if( !isIgnored( attribute ) ) ) yield getAttribute(attribute) }
      { if( axisType == AxisType.Time ) timeRegularSpecs match { case None => Unit; case Some((t0,dt)) => <values start={"%.3f".format(t0)} increment={"%.6f".format(dt)}/> } }
      { if( (axisType != AxisType.Time) && (axisType != AxisType.RunTime) ) variable match {
        case coordVar: CoordinateAxis1D => getData(variable, coordVar.isRegular)
        case _ => getData(variable, false)
      }}
    </variable>
  }

  def getData(variable: nc2.Variable, isRegular: Boolean): xml.Node = {
    val dataArray: Array[Double] = CDDoubleArray.factory(variable.read).getArrayData
    if (isRegular) {
      <values start={"%.3f".format(dataArray(0))} increment={"%.6f".format(dataArray(1)-dataArray(0))}/>
    } else {
      <values>
        {dataArray.map(dv => "%.3f".format(dv)).mkString(" ")}
      </values>
    }
  }

  def getTimeSpecs: Option[(Double,Double)] = {
    val t0 = fileHeaders.head.startValue
    val dt = if(fileHeaders.head.nElem > 1) { fileHeaders.head.axisValues(1)-fileHeaders.head.axisValues(0) } else { fileHeaders(1).startValue - fileHeaders(0).startValue }
    Some( t0 -> dt )
  }

  def getAggregation(timeRegular: Boolean ): xml.Node = {
    <aggregation dimName="time" units="days since 1970-1-1" type="joinExisting">
      { for( fileHeader <- fileHeaders ) yield { getAggDataset(fileHeader,timeRegular) } }
    </aggregation>
  }

  def getNCML: xml.Node = {
    val timeRegularSpecs= None // getTimeSpecs
    println("Processing %d files with %d workers".format( nFiles, nReadProcessors) )
    <netcdf xmlns="http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2">
      <explicit/>
      <attribute name="title" type="string" value="NetCDF aggregated dataset"/>

      { for( attribute <- fileMetadata.attributes ) yield getAttribute(attribute) }
      { for (coordAxis <- fileMetadata.coordinateAxes) yield getDimension(coordAxis) }
      { for (variable <- fileMetadata.variables) yield getVariable( variable, timeRegularSpecs ) }
      { getAggregation( timeRegularSpecs.isDefined ) }

    </netcdf>
  }

  def writeNCML( ncmlFile: File ) = {
    val bw = new BufferedWriter(new FileWriter( ncmlFile ))
    bw.write( getNCML.toString )
    bw.close()
  }
}

object FileHeader extends Loggable {
  val maxOpenAttempts = 4
  val retryIntervalSecs = 30
  def apply( file: File, timeRegular: Boolean ): FileHeader = {
    val axisValues: Array[Double] = FileHeader.getTimeCoordValues(file)
    val path: String = file.getAbsolutePath
    new FileHeader( path, axisValues, timeRegular )
  }

  def factory( files: IndexedSeq[File], workerIndex: Int ): IndexedSeq[FileHeader] = {
    var retryFiles = new ListBuffer[File]()
    val timeRegular = false // getTimeAxisRegularity( files.head )
    val firstPass = for (iFile <- files.indices; file = files(iFile)) yield {
      try {
        val t0 = System.nanoTime()
        val fileHeader = FileHeader( file, timeRegular )
        val t1 = System.nanoTime()
        println("Worker[%d]: Processing file[%d] '%s', start = %.3f, ncoords = %d, time = %.4f ".format(workerIndex, iFile, file.getAbsolutePath, fileHeader.startValue, fileHeader.nElem, (t1 - t0) / 1.0E9))
        Some(fileHeader)
      } catch { case err: Exception =>  retryFiles += file; None }
    }
    val secondPass = for (iFile <- retryFiles.indices; file = retryFiles(iFile)) yield {
      println("Worker[%d]: Reprocessing file[%d] '%s'".format(workerIndex, iFile, file.getAbsolutePath))
      FileHeader(file,timeRegular)
    }
    firstPass.flatten ++ secondPass
  }

  def getTimeAxisRegularity(ncFile: File): Boolean = {
    val ncDataset: NetcdfDataset = openNetCDFFile( ncFile )
    try {
      Option(ncDataset.findCoordinateAxis(AxisType.Time)) match {
        case Some(coordAxis) => coordAxis match {
          case coordAxis: CoordinateAxis1D => coordAxis.isRegular
          case _ => throw new Exception("Multidimensional coord axes not currently supported")
        }
        case None => throw new Exception("ncFile does not have a time axis: " + ncFile.getAbsolutePath)
      }
    } finally { ncDataset.close() }
  }

  def getTimeValues( ncDataset: NetcdfDataset, coordAxis: VariableDS, start_index : Int = 0, end_index : Int = -1, stride: Int = 1 ): Array[Double] = {
    val sec_in_day = 60 * 60 * 24
    val timeAxis: CoordinateAxis1DTime = CoordinateAxis1DTime.factory( ncDataset, coordAxis, new Formatter())
    val timeCalValues: List[CalendarDate] = timeAxis.getCalendarDates.toList
    val timeZero = CalendarDate.of(timeCalValues.head.getCalendar, 1970, 1, 1, 1, 1, 1)
    val last_index = if ( end_index >= start_index ) end_index else ( timeCalValues.length - 1 )
    val time_values = for (index <- (start_index to last_index by stride); calVal = timeCalValues(index)) yield (calVal.getDifferenceInMsecs(timeZero)/1000).toDouble/sec_in_day
    time_values.toArray[Double]
  }

  def openNetCDFFile(ncFile: File, attempt: Int = 0): NetcdfDataset = try {
    NetcdfDataset.openDataset("file:" + ncFile.getAbsolutePath)
  } catch {
    case ex: Throwable =>
      if (attempt == maxOpenAttempts) throw new Exception("Error opening file '%s' after %d attempts (will retry later): '%s'".format(ncFile.getName, maxOpenAttempts, ex.getMessage))
      else {
        Thread.sleep( retryIntervalSecs * 1000 )
        openNetCDFFile(ncFile, attempt + 1)
      }
  }

  def getTimeCoordValues(ncFile: File): Array[Double] = {
    val ncDataset: NetcdfDataset = openNetCDFFile( ncFile )
    Option( ncDataset.findCoordinateAxis( AxisType.Time ) ) match {
      case Some( timeAxis ) =>
        val values = getTimeValues( ncDataset, timeAxis )
        ncDataset.close()
        values
      case None => throw new Exception( "ncFile does not have a time axis: " + ncFile.getAbsolutePath )
    }
  }
}

class DatasetFileHeaders( val aggDim: String, val aggFileMap: Seq[FileHeader] ) {
  def getNElems(): Int = {
    assert( !aggFileMap.isEmpty, "Error, aggregated dataset has no files!" )
    return aggFileMap.head.nElem
  }
  def getAggAxisValues: Array[Double] =
    aggFileMap.foldLeft(Array[Double]()) { _ ++ _.axisValues }
}

class FileHeader( val path: String, val axisValues: Array[Double], val timeRegular: Boolean ) {
  def nElem = axisValues.length
  def startValue = axisValues(0)
  override def toString: String = " *** FileHeader { path='%s', nElem=%d, startValue=%d } ".format( path, nElem, startValue )
}

object FileMetadata {
  def apply(file: File): FileMetadata = new FileMetadata(file)
}

class FileMetadata( val ncFile: File ) {
  private val ncDataset: NetcdfDataset = FileHeader.openNetCDFFile( ncFile )
  val coordinateAxes = ncDataset.getCoordinateAxes.toList
  val dimensions: List[nc2.Dimension] = ncDataset.getDimensions.toList
  val variables = ncDataset.getVariables.toList
  val attributes = ncDataset.getGlobalAttributes
  val dimNames = dimensions.map( _.getShortName )
  def getCoordinateAxis( name: String ): Option[nc2.dataset.CoordinateAxis] = coordinateAxes.find( p => p.getShortName.equalsIgnoreCase(name) )

  def getAxisType( variable: nc2.Variable ): AxisType = variable match {
    case coordVar: CoordinateAxis1D => coordVar.getAxisType;
    case _ => AxisType.RunTime
  }
}

object cdscan extends App {
  val t0 = System.nanoTime()
  val file = NCMLWriter.getCachePath("NCML").resolve( args(0) ).toFile
  assert( ( !file.exists && file.getParentFile.exists ) || file.canWrite, "Error, can't write to NCML file " + file.getAbsolutePath )
  val ncmlWriter = new NCMLWriter( args.tail.iterator )
  val ncmlNode = ncmlWriter.getNCML
  val bw = new BufferedWriter(new FileWriter( file ))
  bw.write( ncmlNode.toString )
  bw.close()
  val t1 = System.nanoTime()
  println( "Writing NcML to file '%s', time = %.4f".format( file.getAbsolutePath, (t1-t0)/1.0E9)  )
}

object NCMLWriterTest extends App {
  val t0 = System.nanoTime()
  val ofile = "/tmp/MERRA300.prod.assim.inst3_3d_asm_Cp.xml"
  val ncmlWriter = new NCMLWriter( Array("/Users/tpmaxwel/Dropbox/Tom/Data/MERRA/DAILY/2005/").iterator )
  val ncmlNode = ncmlWriter.getNCML
  val file = new File( ofile )
  val bw = new BufferedWriter(new FileWriter(file))
  val t1 = System.nanoTime()
  println( "Writing NcML to file '%s', time = %.4f:".format( file.getAbsolutePath, (t1-t0)/1.0E9) )
  val nodeStr = ncmlNode.toString
  println( nodeStr )
  bw.write( nodeStr )
  bw.close()
}


package nasa.nccs.caching

import java.io.{FileInputStream, RandomAccessFile}
import java.nio.channels.FileChannel
import java.nio.{ByteBuffer, FloatBuffer, MappedByteBuffer}

import nasa.nccs.cdapi.cdm.{Collection, _}
import nasa.nccs.cdapi.tensors.{CDByteArray, CDFloatArray}
import nasa.nccs.cds2.loaders.Masks
import nasa.nccs.cds2.utilities.GeoTools
import nasa.nccs.esgf.process.{DataFragmentKey, _}
import nasa.nccs.utilities.Loggable
import ucar.ma2

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

object MaskKey {
  def apply( bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int] ): MaskKey = {
    new MaskKey( bounds, Array( mask_shape(spatial_axis_indices(0)), mask_shape(spatial_axis_indices(1) ) ) )
  }
}
class MaskKey( bounds: Array[Double], dimensions: Array[Int] ) {}

class CacheChunk( val offset: Int, val elemSize: Int, val shape: Array[Int], val buffer: ByteBuffer ) {
  def size: Int = shape.product
  def data: Array[Byte] = buffer.array
  def byteSize = shape.product * elemSize
  def byteOffset = offset * elemSize
}

//class CacheFileReader( val datasetFile: String, val varName: String, val sectionOpt: Option[ma2.Section] = None, val cacheType: String = "fragment" ) extends XmlResource {
//  private val netcdfDataset = NetcdfDataset.openDataset( datasetFile )
// private val ncVariable = netcdfDataset.findVariable(varName)

class FileToCacheStream( val cdVariable: CDSVariable, val fragSpec: DataFragmentSpec, val maskOpt: Option[CDByteArray], val cacheType: String = "fragment"  ) extends Loggable {
  private val ncVariable = cdVariable.ncVariable
  private val chunkCache: Cache[Int,CacheChunk] = new FutureCache("Store",cacheType,false)
  private val nReadProcessors = Runtime.getRuntime.availableProcessors - 1
  private val roi: ma2.Section = fragSpec.roi
  private val baseShape = roi.getShape
  private val dType: ma2.DataType  = ncVariable.getDataType
  private val elemSize = ncVariable.getElementSize
  private val range0 = roi.getRange(0)

  def getChunkMemorySize( chunkSize: Int ) : Int = {
    var full_shape = baseShape.clone()
    full_shape(0) = chunkSize
    full_shape.product * elemSize
  }

  def getCacheFilePath: String = {
    val cache_file = "a" + System.nanoTime.toHexString
    DiskCacheFileMgr.getDiskCacheFilePath(cacheType, cache_file)
  }

  def readDataChunks( nChunks: Int, chunkSize: Int, coreIndex: Int ): Int = {
    var subsection = new ma2.Section(roi)

    var nElementsWritten = 0
    for( iChunk <- (coreIndex until nChunks by nReadProcessors); startLoc = iChunk*chunkSize; if(startLoc < baseShape(0)) ) {
      val endLoc = Math.min( startLoc + chunkSize - 1, baseShape(0)-1 )
      val chunkRange = new ma2.Range( startLoc, endLoc )
      subsection.replaceRange(0,chunkRange)
      val data = ncVariable.read(subsection)
      val chunkShape = subsection.getShape
      val chunk = new CacheChunk( startLoc, elemSize, chunkShape, data.getDataAsByteBuffer )
      chunkCache.put( iChunk, chunk )
      nElementsWritten += chunkShape.product
    }
    nElementsWritten
  }

  def execute( chunkSize: Int ): String = {
    val nChunks = if(chunkSize <= 0) { 1 } else { Math.ceil( range0.length / chunkSize.toFloat ).toInt }
    val readProcFuts: IndexedSeq[Future[Int]] = for( coreIndex <- (0 until Math.min( nChunks, nReadProcessors ) ) ) yield Future { readDataChunks(nChunks,chunkSize,coreIndex) }
    writeChunks(nChunks,chunkSize)
  }

  def cacheFloatData( chunkSize: Int  ): ( String, CDFloatArray ) = {
    assert( dType == ma2.DataType.FLOAT, "Attempting to cache %s data as float: %s".format(dType.toString,cdVariable.name) )
    val cache_id = execute( chunkSize )
    getReadBuffer(cache_id) match {
      case (channel, buffer) =>
        val storage: FloatBuffer = buffer.asFloatBuffer
        channel.close
        ( cache_id -> new CDFloatArray( fragSpec.getShape, storage, cdVariable.missing ) )
    }
  }

  def getReadBuffer( cache_id: String ): ( FileChannel, MappedByteBuffer ) = {
    val channel = new FileInputStream( cache_id ).getChannel
    channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size)
  }

  def writeChunks( nChunks: Int, chunkSize: Int ): String = {
    val cacheFilePath = getCacheFilePath
    val chunkByteSize = getChunkMemorySize( chunkSize )
    val channel = new RandomAccessFile(cacheFilePath,"rw").getChannel()
    logger.info( "Writing Buffer file '%s', nChunks = %d, chunkByteSize = %d, size = %d".format( cacheFilePath, nChunks, chunkByteSize, chunkByteSize * nChunks ))
    var buffer: MappedByteBuffer = channel.map( FileChannel.MapMode.READ_WRITE, 0, chunkByteSize * nChunks  )
    (0 until nChunks).foreach( processChunkFromReader( _, buffer ) )
    cacheFilePath
  }

  def processChunkFromReader( iChunk: Int, buffer: MappedByteBuffer ): Unit = {
    chunkCache.get(iChunk) match {
      case Some( cacheChunkFut: Future[CacheChunk] ) =>
        val cacheChunk = Await.result( cacheChunkFut, Duration.Inf )
//        logger.info( "Writing chunk %d, size = %d, position = %d ".format( iChunk, cacheChunk.byteSize, buffer.position ) )
        buffer.put( cacheChunk.data )
      case None =>
        Thread.sleep( 200 )
        processChunkFromReader( iChunk, buffer )
    }
  }


}

object FragmentPersistence extends DiskCachable with FragSpecKeySet {
  private val fragmentIdCache: Cache[String,String] = new FutureCache("CacheIdMap","fragment",true)
  def getCacheType = "fragment"

  def persist(fragSpec: DataFragmentSpec, frag: PartitionedFragment): Future[String] = {
    val keyStr =  fragSpec.getKey.toStrRep
    fragmentIdCache.get(keyStr) match {
      case Some(fragIdFuture) =>
        fragIdFuture
      case None =>
        logger.info("Persisting Fragment ID for fragment cache recovery: " + fragSpec.toString)
        fragmentIdCache(keyStr) { promiseCacheId(frag) _ }
    }
  }

  def put( key: DataFragmentKey, cache_id: String ) = fragmentIdCache.put( key.toStrRep, cache_id )

  def getEntries: Seq[(String,String)] = fragmentIdCache.getEntries

  def promiseCacheId( frag: PartitionedFragment )(p: Promise[String]): Unit = {
    try {
      val cacheFile = bufferToDiskFloat(frag.data.getSectionData)
      p.success(cacheFile)
    }  catch {
      case err: Throwable => logError(err, "Error writing cache data to disk:"); p.failure(err)
    }
  }

  def restore( cache_id: String, size: Int ): Option[FloatBuffer] = bufferFromDiskFloat( cache_id, size )
  def restore( fragKey: DataFragmentKey ): Option[FloatBuffer] =  fragmentIdCache.get(fragKey.toStrRep).flatMap( restore( _, fragKey.getSize ) )
  def restore( cache_id_future: Future[String], size: Int ): Option[FloatBuffer] = restore( Await.result(cache_id_future, Duration.Inf), size )

  def close(): Unit = {
    Await.result( Future.sequence( fragmentIdCache.values ), Duration.Inf )
    fragmentIdCache.persist()
  }

  def deleteEnclosing( fragSpec: DataFragmentSpec ) =
    findEnclosingFragSpecs(  fragmentIdCache.keys.map( DataFragmentKey(_) ), fragSpec.getKey ).foreach( delete )

  def delete( fragKey: DataFragmentKey ) = {
    fragmentIdCache.get(fragKey.toStrRep) match {
      case Some( cache_id_future ) =>
        val path = DiskCacheFileMgr.getDiskCacheFilePath( getCacheType, Await.result( cache_id_future, Duration.Inf ) )
        fragmentIdCache.remove( fragKey.toStrRep )
        if(new java.io.File(path).delete()) logger.info( s"Deleting persisted fragment file '$path', frag: " + fragKey.toString )
        else logger.warn( s"Failed to delete persisted fragment file '$path'" )
      case None => logger.warn( "No Cache ID found for Fragment: " + fragKey.toString )
    }
  }

  def getEnclosingFragmentData( fragSpec: DataFragmentSpec ): Option[ ( DataFragmentKey, FloatBuffer ) ] = {
    val fragKeys = findEnclosingFragSpecs(  fragmentIdCache.keys.map( DataFragmentKey(_) ), fragSpec.getKey )
    fragKeys.headOption match {
      case Some( fkey ) => restore(fkey) match {
        case Some(array) => Some( (fkey->array) )
        case None => None
      }
      case None => None
    }
  }
  def getFragmentData( fragSpec: DataFragmentSpec ): Option[ FloatBuffer ] = restore( fragSpec.getKey )
}

trait FragSpecKeySet extends nasa.nccs.utilities.Loggable {

  def getFragSpecsForVariable(keys: Set[DataFragmentKey], collection: String, varName: String): Set[DataFragmentKey] = keys.filter(
    _ match {
      case fkey: DataFragmentKey => fkey.sameVariable(collection, varName)
      case x => logger.warn("Unexpected fragment key type: " + x.getClass.getName); false
    }).map(_ match { case fkey: DataFragmentKey => fkey })


  def findEnclosingFragSpecs(keys: Set[DataFragmentKey], fkey: DataFragmentKey, admitEquality: Boolean = true): Set[DataFragmentKey] = {
    val variableFrags = getFragSpecsForVariable(keys, fkey.collectionUrl, fkey.varname)
    variableFrags.filter(fkeyParent => fkeyParent.contains(fkey, admitEquality))
  }

  def findEnclosedFragSpecs(keys: Set[DataFragmentKey], fkeyParent: DataFragmentKey, admitEquality: Boolean = false): Set[DataFragmentKey] = {
    val variableFrags = getFragSpecsForVariable(keys, fkeyParent.collectionUrl, fkeyParent.varname)
    variableFrags.filter(fkey => fkeyParent.contains(fkey, admitEquality))
  }

  def findEnclosingFragSpec(keys: Set[DataFragmentKey], fkeyChild: DataFragmentKey, selectionCriteria: FragmentSelectionCriteria.Value, admitEquality: Boolean = true): Option[DataFragmentKey] = {
    val enclosingFragments = findEnclosingFragSpecs(keys, fkeyChild, admitEquality)
    if (enclosingFragments.isEmpty) None
    else Some(selectionCriteria match {
      case FragmentSelectionCriteria.Smallest => enclosingFragments.minBy(_.getRoi.computeSize())
      case FragmentSelectionCriteria.Largest => enclosingFragments.maxBy(_.getRoi.computeSize())
    })
  }
}

class CollectionDataCacheMgr extends nasa.nccs.esgf.process.DataLoader with FragSpecKeySet {
  private val fragmentCache: Cache[DataFragmentKey,PartitionedFragment] = new FutureCache("Store","fragment",false)
  private val datasetCache: Cache[String,CDSDataset] = new FutureCache("Store","dataset",false)
  private val variableCache: Cache[String,CDSVariable] = new FutureCache("Store","variable",false)
  private val maskCache: Cache[MaskKey,CDByteArray] = new FutureCache("Store","mask",false)
  def clearFragmentCache() = fragmentCache.clear

  def makeKey(collection: String, varName: String) = collection + ":" + varName

  def extractFuture[T](key: String, result: Option[Try[T]]): T = result match {
    case Some(tryVal) => tryVal match {
      case Success(x) => x;
      case Failure(t) => throw t
    }
    case None => throw new Exception(s"Error getting cache value $key")
  }

  def getDatasetFuture(collection: Collection, varName: String): Future[CDSDataset] =
    datasetCache(makeKey(collection.url, varName)) { produceDataset(collection, varName) _ }

  def getDataset(collection: Collection, varName: String): CDSDataset = {
    val futureDataset: Future[CDSDataset] = getDatasetFuture(collection, varName)
    Await.result(futureDataset, Duration.Inf)
  }

  private def produceDataset(collection: Collection, varName: String)(p: Promise[CDSDataset]): Unit = {
    val t0 = System.nanoTime()
    val dataset = CDSDataset.load(collection, varName)
    val t1 = System.nanoTime()
    logger.info(" Completed reading dataset (%s:%s), T: %.4f ".format( collection, varName, (t1-t0)/1.0E9 ))
    p.success(dataset)
  }


  private def promiseVariable(collection: Collection, varName: String)(p: Promise[CDSVariable]): Unit =
    getDatasetFuture(collection, varName) onComplete {
      case Success(dataset) =>
        try {
          val t0 = System.nanoTime()
          val variable = dataset.loadVariable(varName)
          val t1 = System.nanoTime()
          logger.info(" Completed reading variable %s, T: %.4f".format( varName, (t1-t0)/1.0E9 ) )
          p.success(variable)
        }
        catch {
          case e: Exception => p.failure(e)
        }
      case Failure(t) => p.failure(t)
    }

  def getVariableFuture(collection: Collection, varName: String): Future[CDSVariable] = variableCache(makeKey(collection.url, varName)) {
    promiseVariable(collection, varName) _
  }

  def getVariable(collection: Collection, varName: String): CDSVariable = {
    val futureVariable: Future[CDSVariable] = getVariableFuture(collection, varName)
    Await.result(futureVariable, Duration.Inf)
  }

  def getVariable(fragSpec: DataFragmentSpec): CDSVariable = getVariable(fragSpec.collection, fragSpec.varname)

  private def cutExistingFragment( fragSpec: DataFragmentSpec, abortSizeFraction: Float=0f ): Option[PartitionedFragment] = {
    val fragOpt = findEnclosingFragSpec( fragmentCache.keys, fragSpec.getKey, FragmentSelectionCriteria.Smallest) match {
      case Some(fkey: DataFragmentKey) => getExistingFragment(fkey) match {
        case Some(fragmentFuture) =>
          if (!fragmentFuture.isCompleted && (fkey.getSize * abortSizeFraction > fragSpec.getSize)) {
            logger.info("Cache Chunk[%s] found but not yet ready, abandoning cache access attempt".format(fkey.shape.mkString(",")))
            None
          } else {
            val fragment = Await.result(fragmentFuture, Duration.Inf)
            Some(fragment.cutNewSubset(fragSpec.roi))
          }
        case None => cutExistingFragment(fragSpec, abortSizeFraction)
      }
      case None => None
    }
    fragOpt match {
      case None =>
        FragmentPersistence.getEnclosingFragmentData(fragSpec) match {
          case Some((fkey, fltBuffer)) =>
            val cdvar: CDSVariable = getVariable(fragSpec.collection, fragSpec.varname )
            val newFragSpec = fragSpec.reSection(fkey)
            val maskOpt = newFragSpec.mask.flatMap( maskId => produceMask( maskId, newFragSpec.getBounds, newFragSpec.getGridShape, cdvar.getTargetGrid( newFragSpec ).getAxisIndices("xy") ) )
            val fragment = new PartitionedFragment( new CDFloatArray( newFragSpec.getShape, fltBuffer, cdvar.missing ), maskOpt, newFragSpec )
            fragmentCache.put( fkey, fragment )
            Some(fragment.cutNewSubset(fragSpec.roi))
          case None => None
        }
      case x => x
    }
  }

  private def promiseFragment( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode )(p: Promise[PartitionedFragment]): Unit =
    getVariableFuture( fragSpec.collection, fragSpec.varname )  onComplete {
      case Success(variable) =>
        try {
          val t0 = System.nanoTime()
          val result = fragSpec.targetGridOpt match {
            case Some( targetGrid ) =>
              val maskOpt = fragSpec.mask.flatMap( maskId => produceMask( maskId, fragSpec.getBounds, fragSpec.getGridShape, targetGrid.getAxisIndices("xy") ) )
              targetGrid.loadRoi( variable, fragSpec, maskOpt, dataAccessMode )
            case None =>
              val targetGrid = new TargetGrid( variable, Some(fragSpec.getAxes) )
              val maskOpt = fragSpec.mask.flatMap( maskId => produceMask( maskId, fragSpec.getBounds, fragSpec.getGridShape, targetGrid.getAxisIndices("xy")  ) )
              targetGrid.loadRoi( variable, fragSpec, maskOpt, dataAccessMode )
          }
          logger.info("Completed variable (%s:%s) subset data input in time %.4f sec, section = %s ".format(fragSpec.collection, fragSpec.varname, (System.nanoTime()-t0)/1.0E9, fragSpec.roi ))
          //          logger.info("Data column = [ %s ]".format( ( 0 until result.shape(0) ).map( index => result.getValue( Array(index,0,100,100) ) ).mkString(", ") ) )
          p.success( result )

        } catch { case e: Exception => p.failure(e) }
      case Failure(t) => p.failure(t)
    }

  def produceMask(maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int]): Option[CDByteArray] = {
    if (Masks.isMaskId(maskId)) {
      val maskFuture = getMaskFuture( maskId, bounds, mask_shape, spatial_axis_indices  )
      val result = Await.result( maskFuture, Duration.Inf )
      logger.info("Loaded mask (%s) data".format( maskId ))
      Some(result)
    } else {
      None
    }
  }

  private def getMaskFuture( maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int]  ): Future[CDByteArray] = {
    val fkey = MaskKey(bounds, mask_shape, spatial_axis_indices)
    val maskFuture = maskCache( fkey ) { promiseMask( maskId, bounds, mask_shape, spatial_axis_indices ) _ }
    logger.info( ">>>>>>>>>>>>>>>> Put mask in cache: " + fkey.toString + ", keys = " + maskCache.keys.mkString("[",",","]") )
    maskFuture
  }

  private def promiseMask( maskId: String, bounds: Array[Double], mask_shape: Array[Int], spatial_axis_indices: Array[Int] )(p: Promise[CDByteArray]): Unit =
    try {
      Masks.getMask(maskId) match {
        case Some(mask) => mask.mtype match {
          case "shapefile" =>
            val geotools = new GeoTools()
            p.success( geotools.produceMask( mask.getPath, bounds, mask_shape, spatial_axis_indices ) )
          case x => p.failure(new Exception(s"Unrecognized Mask type: $x"))
        }
        case None => p.failure(new Exception(s"Unrecognized Mask ID: $maskId: options are %s".format(Masks.getMaskIds)))
      }
    } catch { case e: Exception => p.failure(e) }

  private def clearRedundantFragments( fragSpec: DataFragmentSpec ) = findEnclosedFragSpecs( fragmentCache.keys, fragSpec.getKey ).foreach( fragmentCache.remove )

  private def getFragmentFuture( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode  ): Future[PartitionedFragment] = {
    val fragFuture = fragmentCache( fragSpec.getKey ) { promiseFragment( fragSpec, dataAccessMode ) _ }
    fragFuture onComplete {
      case Success(fragment) => try {
        logger.info( " Persisting fragment spec: " + fragSpec.getKey.toString )
        //          clearRedundantFragments(fragSpec)
        if (dataAccessMode == DataAccessMode.Cache) FragmentPersistence.persist(fragSpec, fragment)
      } catch {
        case err: Throwable =>  logger.warn( " Failed to persist fragment list due to error: " + err.getMessage )
      }
      case Failure(err) => logger.warn( " Failed to generate fragment due to error: " + err.getMessage )
    }
    logger.info( ">>>>>>>>>>>>>>>> Put frag in cache: " + fragSpec.toString + ", keys = " + fragmentCache.keys.mkString("[",",","]") + ", dataAccessMode = " + dataAccessMode.toString )
    fragFuture
  }

  def getFragment( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode, abortSizeFraction: Float=0f  ): PartitionedFragment = {
    cutExistingFragment(fragSpec, abortSizeFraction) getOrElse {
      val fragmentFuture = getFragmentFuture(fragSpec, dataAccessMode)
      val result = Await.result(fragmentFuture, Duration.Inf)
      logger.info("Loaded variable (%s:%s) subset data, section = %s ".format(fragSpec.collection, fragSpec.varname, fragSpec.roi))
      result
    }
  }

  def getFragmentAsync( fragSpec: DataFragmentSpec, dataAccessMode: DataAccessMode  ): Future[PartitionedFragment] =
    cutExistingFragment(fragSpec) match {
      case Some(fragment) => Future { fragment }
      case None => getFragmentFuture(fragSpec, dataAccessMode)
    }


  //  def loadOperationInputFuture( dataContainer: DataContainer, domain_container: DomainContainer ): Future[OperationInputSpec] = {
  //    val variableFuture = getVariableFuture(dataContainer.getSource.collection, dataContainer.getSource.name)
  //    variableFuture.flatMap( variable => {
  //      val section = variable.getSubSection(domain_container.axes)
  //      val fragSpec = variable.createFragmentSpec( section, domain_container.mask )
  //      val axisSpecs: AxisIndices = variable.getAxisIndices(dataContainer.getOpSpecs)
  //      for (frag <- getFragmentFuture(fragSpec)) yield new OperationInputSpec( fragSpec, axisSpecs)
  //    })
  //  }
  //
  //  def loadDataFragmentFuture( dataContainer: DataContainer, domain_container: DomainContainer ): Future[PartitionedFragment] = {
  //    val variableFuture = getVariableFuture(dataContainer.getSource.collection, dataContainer.getSource.name)
  //    variableFuture.flatMap( variable => {
  //      val section = variable.getSubSection(domain_container.axes)
  //      val fragSpec = variable.createFragmentSpec( section, domain_container.mask )
  //      for (frag <- getFragmentFuture(fragSpec)) yield frag
  //    })
  //  }

  def getExistingMask( fkey: MaskKey  ): Option[Future[CDByteArray]] = {
    val rv: Option[Future[CDByteArray]] = maskCache.get( fkey )
    logger.info( ">>>>>>>>>>>>>>>> Get mask from cache: search key = " + fkey.toString + ", existing keys = " + maskCache.keys.mkString("[",",","]") + ", Success = " + rv.isDefined.toString )
    rv
  }

  def getExistingFragment( fkey: DataFragmentKey  ): Option[Future[PartitionedFragment]] = {
    val rv: Option[Future[PartitionedFragment]] = fragmentCache.get( fkey )
    logger.info( ">>>>>>>>>>>>>>>> Get frag from cache: search key = " + fkey.toString + ", existing keys = " + fragmentCache.keys.mkString("[",",","]") + ", Success = " + rv.isDefined.toString )
    rv
  }
}

object collectionDataCache extends CollectionDataCacheMgr()
//object cacheWriterTest extends App {
//  val data_file = "/usr/local/web/data/MERRA/MERRA300.prod.assim.inst3_3d_asm_Cp.xml"
//  val netcdfDataset = NetcdfDataset.openDataset( data_file )
//  val varName = "t"
//  val ncVariable = netcdfDataset.findVariable(varName)
//  var shape = ncVariable.getShape
//  var section: ma2.Section = new ma2.Section(shape)
//
//
//  println(".")
//}

//dType match {
//  case ma2.DataType.FLOAT =>
//}



package nasa.nccs.cds2.modules.CDS

import nasa.nccs.cdapi.cdm._
import nasa.nccs.cdapi.kernels._
import nasa.nccs.cdapi.tensors.{CDCoordMap, CDFloatArray, CDTimeCoordMap}
import nasa.nccs.cds2.kernels.KernelTools
import nasa.nccs.esgf.process._
import ucar.ma2

class CDS extends KernelModule with KernelTools {
  override val version = "1.0-SNAPSHOT"
  override val organization = "nasa.nccs"
  override val author = "Thomas Maxwell"
  override val contact = "thomas.maxwell@nasa.gov"

  class max extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Maximum over Axes on Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = axisSpecs.getAxes
      val t10 = System.nanoTime
      val max_val_masked: CDFloatArray = input_array.max( axes.toArray )
      val t11 = System.nanoTime
      logger.info("Max_val_masked, time = %.4f s, result = %s".format( (t11-t10)/1.0E9, max_val_masked.toString ) )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.getReducedSection(Set(axes:_*))
      if(async) {
        new AsyncExecutionResult( saveResult( max_val_masked, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), max_val_masked )
    }
  }

  class const extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Sets Input Fragment to constant value"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val async = requestCx.config("async", "false").toBoolean
      val sval = operationCx.config("value", "1.0" )
      val t10 = System.nanoTime
      val max_val_masked: CDFloatArray = ( input_array := sval.toFloat )
      val t11 = System.nanoTime
      logger.info("Constant op, time = %.4f s, result = %s".format( (t11-t10)/1.0E9, max_val_masked.toString ) )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.getReducedSection(Set())
      if(async) {
        new AsyncExecutionResult( saveResult( max_val_masked, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), max_val_masked )
    }
  }

  class min extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Minimum over Axes on Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = axisSpecs.getAxes
      val t10 = System.nanoTime
      val max_val_masked: CDFloatArray = input_array.min( axes.toArray )
      val t11 = System.nanoTime
      logger.info("Mean_val_masked, time = %.4f s, result = %s".format( (t11-t10)/1.0E9, max_val_masked.toString ) )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.getReducedSection(Set(axes:_*))
      if(async) {
        new AsyncExecutionResult( saveResult( max_val_masked, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), max_val_masked )
    }
  }

  class sum extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Sum over Axes on Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = axisSpecs.getAxes
      val t10 = System.nanoTime
      val max_val_masked: CDFloatArray = input_array.sum( axes.toArray )
      val t11 = System.nanoTime
      logger.info("Mean_val_masked, time = %.4f s, result = %s".format( (t11-t10)/1.0E9, max_val_masked.toString ) )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.getReducedSection(Set(axes:_*))
      if(async) {
        new AsyncExecutionResult( saveResult( max_val_masked, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), max_val_masked )
    }
  }

  class average extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Weighted Average over Axes on Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val optargs: Map[String, String] = operationCx.getConfiguration
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = axisSpecs.getAxes
      val t10 = System.nanoTime
      val weighting_type = operationCx.config("weights", if( operationCx.config("axes","").contains('y') ) "cosine" else "")
      val weightsOpt: Option[CDFloatArray] = weighting_type match {
        case "" => None
        case "cosine" => serverCx.getAxisData( inputVar.getSpec, 'y' ).map( axis_data => input_array.computeWeights( weighting_type, Map( 'y' ->  axis_data ) ) )
        case x => throw new NoSuchElementException( "Can't recognize weighting method: %s".format( x ))
      }
      val mean_val_masked: CDFloatArray = input_array.mean( axes.toArray, weightsOpt )
      val t11 = System.nanoTime
      logger.info("Mean_val_masked, time = %.4f s, result = %s".format( (t11-t10)/1.0E9, mean_val_masked.toString ) )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.getReducedSection(Set(axes:_*))
      if(async) {
        new AsyncExecutionResult( saveResult( mean_val_masked, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), mean_val_masked )
    }
  }

  class subset extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Subset of Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val optargs: Map[String, String] = operationCx.getConfiguration
      val resultFragment = optargs.get("domain") match {
        case None => inputVar.dataFragment
        case Some( domainId ) =>
          serverCx.getSubset( inputVar.dataFragment.fragmentSpec,  requestCx.getDomain( domainId ) )
      }
      val async = requestCx.config("async", "false").toBoolean
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = resultFragment.fragmentSpec.roi
      if(async) {
        new AsyncExecutionResult( saveResult( resultFragment.data, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), resultFragment.data )
    }
  }

  class metadata extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Displays Metadata for available data collections and masks"

    override def execute(operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext): ExecutionResult = {
      import nasa.nccs.cds2.loaders.Collections
      val result: (String, xml.Node) = requestCx.inputs.headOption match {
        case None => ("Collection", Collections.toXml)
        case Some((key, inputSpec)) =>
          inputSpec.data.collection.url match {
            case "" => ("Collection", Collections.toXml)
            case collectionUrl =>
              Collections.findCollection( collectionUrl ) match {
                case Some( collection ) =>
                  inputSpec.data.varname match {
                    case "" => ( collectionUrl, collection.toXml )
                    case vname => ( collectionUrl + ":" + vname, serverCx.getVariable(collection, vname).toXml)
                  }
                case None => ( "Collection", Collections.toXml )
              }
          }
      }
      result match {
        case (id, resultXml) => new XmlExecutionResult("Metadata~" + id, resultXml )
      }
    }

    override def execute(operationCx: OperationContext, serverCx: ServerContext): ExecutionResult = {
      import nasa.nccs.cds2.loaders.Collections
      new XmlExecutionResult("Metadata~Collection", Collections.toXml )
    }
  }

//  class aggregate extends Kernel {
//    val inputs = List(Port("input fragment", "1"))
//    val outputs = List(Port("result", "1"))
//    override val description = "Aggregate data into bins using specified reduce function"
//
//    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
//      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
//      val optargs: Map[String, String] = operationCx.getConfiguration
//      val input_array: CDFloatArray = inputVar.dataFragment.data
//      val cdsVariable = serverCx.getVariable(inputVar.getSpec)
//      val axisSpecs = inputVar.axisIndices
//      val async = requestCx.config("async", "false").toBoolean
//      val axes = axisSpecs.getAxes.toArray
//      val binArgs = optargs.getOrElse("bins","").split('|')
//      val cycle = if(binArgs.length > 3) binArgs(3) else ""
//      val period = if(binArgs.length > 1) binArgs(1) else ""
//      val opName = if(binArgs.length > 2) binArgs(2) else "ave"
//      val t10 = System.nanoTime
//      assert(axes.length == 1, "Must bin over 1 axis only! Requested: " + axes.mkString(","))
//      val coordMap: CDCoordMap = CDTimeCoordMap.getTimeCycleMap( period, cycle, requestCx.targetGrid )
//      val binned_array: CDFloatArray = input_array.weightedReduce(input_array.getOp("add"), axes, 0f, None, Some(coordMap)) match {
//        case (values_sum: CDFloatArray, weights_sum: CDFloatArray) =>
//          values_sum / weights_sum
//      }
//      val t11 = System.nanoTime
//      logger.info("Binned array, time = %.4f s, result = %s".format((t11 - t10) / 1.0E9, binned_array.toString))
//      val variable = serverCx.getVariable(inputVar.getSpec)
//      val section = inputVar.getSpec.getReducedSection(Set(axes(0)), binned_array.getShape(axes(0)))
//      if (async) {
//        new AsyncExecutionResult(saveResult(binned_array, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx)))
//      }
//      else new BlockingExecutionResult(operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), binned_array)
//    }
//  }

  class timeBin extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Aggregate data into bins using specified reduce function"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput = inputVars(operationCx, requestCx, serverCx).head
      val optargs: Map[String, String] = operationCx.getConfiguration
      val input_array: CDFloatArray = inputVar.dataFragment.data
      val cdsVariable = serverCx.getVariable(inputVar.getSpec.collection, inputVar.getSpec.varname)
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = requestCx.targetGrid.getAxisIndices("t")

      val period = getIntArg( optargs, "period", Some(1) )
      val mod = getIntArg( optargs, "mod", Some(Int.MaxValue) )
      val unit = getStringArg( optargs, "unit" )
      val offset = getIntArg( optargs, "offset", Some(0) )

      val t10 = System.nanoTime
      val cdTimeCoordMap: CDTimeCoordMap = new CDTimeCoordMap(requestCx.targetGrid)
      val coordMap: CDCoordMap = cdTimeCoordMap.getTimeCycleMap( period, unit, mod, offset )
      val timeData  = cdTimeCoordMap.getTimeIndexIterator( "month" ).toArray
      logger.info( "Binned array, timeData = [ %s ]".format( timeData.mkString(",") ) )
      logger.info( "Binned array, coordMap = %s".format( coordMap.toString ) )
      val binned_array: CDFloatArray = input_array.weightedReduce(input_array.getOp("add"), axes, 0f, None, Some(coordMap)) match {
        case (values_sum: CDFloatArray, weights_sum: CDFloatArray) =>
          values_sum / weights_sum
      }
      val t11 = System.nanoTime
      logger.info("Binned array, time = %.4f s, result = %s".format((t11 - t10) / 1.0E9, binned_array.toString))
      val variable = serverCx.getVariable(inputVar.getSpec.collection, inputVar.getSpec.varname)
      val section = inputVar.getSpec.getReducedSection(Set(axes(0)), binned_array.getShape(axes(0)))
      if (async) {
        new AsyncExecutionResult(saveResult(binned_array, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx)))
      }
      else new BlockingExecutionResult(operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), binned_array)
    }
  }

  class anomaly extends Kernel {
    val inputs = List(Port("input fragment", "1"))
    val outputs = List(Port("result", "1"))
    override val description = "Anomaly over Input Fragment"

    override def execute( operationCx: OperationContext, requestCx: RequestContext, serverCx: ServerContext ): ExecutionResult = {
      val inputVar: KernelDataInput  =  inputVars( operationCx, requestCx, serverCx ).head
      val optargs: Map[String,String] =  operationCx.getConfiguration
      val input_array = inputVar.dataFragment.data
      val axisSpecs = inputVar.axisIndices
      val async = requestCx.config("async", "false").toBoolean
      val axes = axisSpecs.getAxes
      val t10 = System.nanoTime
      val weighting_type = requestCx.config("weights", if( operationCx.config("axis","").contains('y') ) "cosine" else "")
      val weightsOpt: Option[CDFloatArray] = weighting_type match {
        case "" => None
        case wtype => serverCx.getAxisData( inputVar.getSpec, 'y' ).map( axis_data => input_array.computeWeights( wtype, Map( 'y' ->  axis_data ) ) )
      }
      val anomaly_result: CDFloatArray = input_array.anomaly( axes.toArray, weightsOpt )
      val variable = serverCx.getVariable( inputVar.getSpec.collection, inputVar.getSpec.varname )
      val section = inputVar.getSpec.roi
      val t11 = System.nanoTime
      logger.info("Anomaly, time = %.4f s".format( (t11-t10)/1.0E9 ) )
      if(async) {
        new AsyncExecutionResult( saveResult( anomaly_result, requestCx, serverCx, requestCx.targetGrid.getSubGrid(section), inputVar.getVariableMetadata(serverCx), inputVar.getDatasetMetadata(serverCx) ) )
      }
      else new BlockingExecutionResult( operationCx.identifier, List(inputVar.getSpec), requestCx.targetGrid.getSubGrid(section), anomaly_result )
    }
  }
}

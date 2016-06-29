import nasa.nccs.caching.{ FragmentPersistence, collectionDataCache }
import nasa.nccs.cdapi.kernels.{BlockingExecutionResult, ErrorExecutionResult}
import nasa.nccs.cdapi.tensors.CDFloatArray
import nasa.nccs.cds2.engine.CDS2ExecutionManager
import nasa.nccs.esgf.process.{OperationInputSpec, RequestContext, TaskRequest}
import org.scalatest._

class ExecutionSpec extends TestSuite(0, 0, 0f, 0f ) {

  test("Sum") {
    val nco_verified_result = 4.886666e+07
    val dataInputs = getSpatialDataInputs(merra_data, ( "axes"->"xy"))
    val result_value: Float = computeValue("CDS.sum", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Sum")
  }

  test("Sum Constant") {
    val nco_verified_result = 180749.0
    val dataInputs = getSpatialDataInputs(const_data, ( "axes"->"xy"))
    val result_value: Float = computeValue("CDS.sum", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Sum")
  }

  test("Maximum") {
    val nco_verified_result = 291.1066
    val dataInputs = getSpatialDataInputs(merra_data, ( "axes"->"xy"))
    val result_value: Float = computeValue("CDS.max", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Maximum")
  }

  test("Minimum") {
    val nco_verified_result = 239.4816
    val dataInputs = getSpatialDataInputs(merra_data, ( "axes"->"xy"))
    val result_value: Float = computeValue("CDS.min", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Minimum")
  }

  test("Persistence") {
    val dataInputs = getSubsetDataInputs( merra_data )
    val request_context: RequestContext = getRequestContext( "CDS.metadata", dataInputs )
    for( ospec <- request_context.inputs.values ) {
      FragmentPersistence.deleteEnclosing(ospec.data)
    }
    val result_array1: CDFloatArray = computeArray("CDS.subset", dataInputs)
    collectionDataCache.clearFragmentCache
    val result_array2: CDFloatArray = computeArray("CDS.subset", dataInputs)
    val max_diff = maxDiff( result_array1, result_array2 )
    println(s"Test Result: %.4f".format( max_diff ) )
    assert(max_diff == 0.0, " Persisted data differs from original data" )
  }

  test("Anomaly") {
    readVerificationData( "/data/ta_anomaly_0_0.nc", "ta" ) match {
      case Some( nco_verified_result ) =>
        val dataInputs = getTemporalDataInputs(merra_data, 0, ( "axes"->"t") )
        val result_values = computeArray("CDS.anomaly", dataInputs)
        val max_scaled_diff = maxScaledDiff(result_values, nco_verified_result)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), nco_verified_result.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Anomaly")
      case None => fail( "Error reading verification data")
    }
  }

  test("Subset(d0)") {
    readVerificationData( "/data/ta_subset_0_0.nc", "ta" ) match {
      case Some( nco_verified_result ) =>
        val dataInputs = getTemporalDataInputs(merra_data, 0, ( "axes"->"t") )
        val result_values = computeArray("CDS.subset", dataInputs)
        val max_scaled_diff = maxScaledDiff(result_values, nco_verified_result)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), nco_verified_result.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Subset")
      case None => fail( "Error reading verification data")
    }
  }

  test("Subset(d0) with secondary domain (d1)") {
    readVerificationData( "/data/ta_subset_0_0.nc", "ta" ) match {
      case Some( nco_verified_result ) =>
        val time_index = 3
        val verified_result_array = nco_verified_result.section( Array(time_index,0,0,0), Array(1,1,1,1) )
        val dataInputs = getTemporalDataInputs(merra_data, time_index, ( "domain"->"d1") )
        val result_values = computeArray("CDS.subset", dataInputs)
        val max_scaled_diff = maxScaledDiff(result_values,verified_result_array)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), verified_result_array.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Subset")
      case None => fail( "Error reading verification data")
    }
  }

  test("Spatial Average") {
    val nco_verified_result = 270.092
    val dataInputs = getSpatialDataInputs(merra_data, ( "axes"->"xy"), ( "weights"->"") )
    val result_value: Float = computeValue("CDS.average", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Spatial Average")
  }

//  test("Variable Metadata") {
//    val dataInputs = getMetaDataInputs( "collection://MERRA/mon/atmos", "ta" )
//    val result_node = computeXmlNode("CDS.metadata", dataInputs)
//    result_node.attribute("shape") match {
//      case Some( shape_attr ) => assert( shape_attr.text == "[432 42 361 540]", " Incorrect shape attribute, should be [432 42 361 540]: " + shape_attr.text )
//      case None => fail( " Missing 'shape' attribute in result: " + result_node.toString )
//    }
//  }

  test("Weighted Spatial Average") {
    val nco_verified_result = 275.4043
    val dataInputs = getSpatialDataInputs(merra_data, ( "axes"->"xy"), ( "weights"->"cosine") )
    val result_value: Float = computeValue("CDS.average", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Weighted Spatial Average")
  }

  test("Weighted Masked Spatial Average") {
    val nco_verified_result = 275.4317
    val dataInputs = getMaskedSpatialDataInputs(merra_data, ( "axes"->"xy"), ( "weights"->"cosine") )
    val result_value: Float = computeValue("CDS.average", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Weighted Masked Spatial Average")
  }

  test("Yearly Cycle") {
    readVerificationData( "/data/ta_subset_0_0.nc", "ta" ) match {
      case Some( nco_subsetted_timeseries ) =>
        val dataInputs = getTemporalDataInputs( merra_data, 0, ( "unit"->"month"), ( "period"->"1"), ( "mod"->"12")  )
        val result_values = computeArray("CDS.timeBin", dataInputs)
        val nco_verified_result = computeCycle( nco_subsetted_timeseries, 12 )
        val max_scaled_diff = maxScaledDiff(result_values, nco_verified_result)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), nco_verified_result.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Yearly Cycle")
      case None => fail( "Error reading verification data")
    }
  }

  test("Seasonal Cycle") {
    readVerificationData( "/data/ta_subset_0_0.nc", "ta" ) match {
      case Some( nco_subsetted_timeseries ) =>
        val dataInputs = getTemporalDataInputs(merra_data, 0, ( "unit"->"month"), ( "period"->"3"), ( "mod"->"4"), ( "offset"->"2") )
        val result_values = computeArray("CDS.timeBin", dataInputs)
        val nco_verified_result = computeSeriesAverage( nco_subsetted_timeseries, 3, 2, 4 )
        val max_scaled_diff = maxScaledDiff(result_values, nco_verified_result)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), nco_verified_result.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Yearly Cycle")
      case None => fail( "Error reading verification data")
    }
  }

  test("Yearly Means") {
    readVerificationData( "/data/ta_subset_0_0.nc", "ta" ) match {
      case Some( nco_subsetted_timeseries ) =>
        val dataInputs = getTemporalDataInputs(merra_data, 0, ( "unit"->"month"), ( "period"->"12") )
        val result_values = computeArray("CDS.timeBin", dataInputs)
        val nco_verified_result = computeSeriesAverage( nco_subsetted_timeseries, 12 )
        val max_scaled_diff = maxScaledDiff(result_values, nco_verified_result)
        println("Test Result: (%s)\n NCO Result: (%s)\n Max_scaled_diff: %f".format(result_values.toString(), nco_verified_result.toString(), max_scaled_diff))
        assert(max_scaled_diff < eps, s" Incorrect timeseries computed for Yearly Ave")
        assert( result_values.getSize == 11, "Wrong size result in Yearly Means")
      case None => fail( "Error reading verification data")
    }
  }

  test("Spatial Average Constant") {
    val nco_verified_result = 1.0
    val dataInputs = getSpatialDataInputs(const_data, ( "axes"->"xy"), ( "weights"->"") )
    val result_value: Float = computeValue("CDS.average", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Spatial Average Constant")
  }

  test("Weighted Spatial Average Constant") {
    val nco_verified_result = 1.0
    val dataInputs = getSpatialDataInputs(const_data, ( "axes"->"xy"), ( "weights"->"cosine") )
    val result_value: Float = computeValue("CDS.average", dataInputs)
    println(s"Test Result:  $result_value, NCO Result: $nco_verified_result")
    assert(Math.abs(result_value - nco_verified_result) / nco_verified_result < eps, s" Incorrect value ($result_value vs $nco_verified_result) computed for Spatial Average Constant with Weights")
  }
}
//  object ComputeWeightsAve extends App {
//    val lats = List[Double]( -90, -89.5, -89, -88.5, -88, -87.5, -87, -86.5, -86, -85.5, -85,
//      -84.5, -84, -83.5, -83, -82.5, -82, -81.5, -81, -80.5, -80, -79.5, -79,
//      -78.5, -78, -77.5, -77, -76.5, -76, -75.5, -75, -74.5, -74, -73.5, -73,
//      -72.5, -72, -71.5, -71, -70.5, -70, -69.5, -69, -68.5, -68, -67.5, -67,
//      -66.5, -66, -65.5, -65, -64.5, -64, -63.5, -63, -62.5, -62, -61.5, -61,
//      -60.5, -60, -59.5, -59, -58.5, -58, -57.5, -57, -56.5, -56, -55.5, -55,
//      -54.5, -54, -53.5, -53, -52.5, -52, -51.5, -51, -50.5, -50, -49.5, -49,
//      -48.5, -48, -47.5, -47, -46.5, -46, -45.5, -45, -44.5, -44, -43.5, -43,
//      -42.5, -42, -41.5, -41, -40.5, -40, -39.5, -39, -38.5, -38, -37.5, -37,
//      -36.5, -36, -35.5, -35, -34.5, -34, -33.5, -33, -32.5, -32, -31.5, -31,
//      -30.5, -30, -29.5, -29, -28.5, -28, -27.5, -27, -26.5, -26, -25.5, -25,
//      -24.5, -24, -23.5, -23, -22.5, -22, -21.5, -21, -20.5, -20, -19.5, -19,
//      -18.5, -18, -17.5, -17, -16.5, -16, -15.5, -15, -14.5, -14, -13.5, -13,
//      -12.5, -12, -11.5, -11, -10.5, -10, -9.5, -9, -8.5, -8, -7.5, -7, -6.5,
//      -6, -5.5, -5, -4.5, -4, -3.5, -3, -2.5, -2, -1.5, -1, -0.5, 0, 0.5, 1,
//      1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 9.5, 10,
//      10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 15.5, 16, 16.5, 17,
//      17.5, 18, 18.5, 19, 19.5, 20, 20.5, 21, 21.5, 22, 22.5, 23, 23.5, 24,
//      24.5, 25, 25.5, 26, 26.5, 27, 27.5, 28, 28.5, 29, 29.5, 30, 30.5, 31,
//      31.5, 32, 32.5, 33, 33.5, 34, 34.5, 35, 35.5, 36, 36.5, 37, 37.5, 38,
//      38.5, 39, 39.5, 40, 40.5, 41, 41.5, 42, 42.5, 43, 43.5, 44, 44.5, 45,
//      45.5, 46, 46.5, 47, 47.5, 48, 48.5, 49, 49.5, 50, 50.5, 51, 51.5, 52,
//      52.5, 53, 53.5, 54, 54.5, 55, 55.5, 56, 56.5, 57, 57.5, 58, 58.5, 59,
//      59.5, 60, 60.5, 61, 61.5, 62, 62.5, 63, 63.5, 64, 64.5, 65, 65.5, 66,
//      66.5, 67, 67.5, 68, 68.5, 69, 69.5, 70, 70.5, 71, 71.5, 72, 72.5, 73,
//      73.5, 74, 74.5, 75, 75.5, 76, 76.5, 77, 77.5, 78, 78.5, 79, 79.5, 80,
//      80.5, 81, 81.5, 82, 82.5, 83, 83.5, 84, 84.5, 85, 85.5, 86, 86.5, 87,
//      87.5, 88, 88.5, 89, 89.5, 90)
//    var sum = 0f
//    var count = 0
//    for ( lval <- lats) {
//      sum = sum + Math.cos( lval*Math.PI/180.0 ).toFloat
//      count = count + 1
//    }
//    println( sum/count )
//  }
//}

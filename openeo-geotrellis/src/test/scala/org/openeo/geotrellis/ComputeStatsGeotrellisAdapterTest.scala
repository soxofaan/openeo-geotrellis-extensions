package org.openeo.geotrellis

import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
import java.time.{LocalDate, LocalTime, ZoneOffset, ZonedDateTime}
import java.util

import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster.histogram.Histogram
import geotrellis.raster.{ByteCells, ByteConstantTile, DoubleConstantNoDataCellType, MultibandTile}
import geotrellis.spark.tiling.LayoutDefinition
import geotrellis.spark.util.SparkUtils
import geotrellis.spark.{ContextRDD, Metadata, SpaceTimeKey, TileLayerMetadata}
import geotrellis.vector.io._
import geotrellis.vector.{Extent, Polygon}
import org.apache.hadoop.hdfs.HdfsConfiguration
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.{AfterClass, Before, BeforeClass, Test}
import org.openeo.geotrellisaccumulo.PyramidFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object ComputeStatsGeotrellisAdapterTest {
  private var sc: SparkContext = _

  @Parameters(name = "PixelThreshold: {0}") def data: java.lang.Iterable[Array[Integer]] = {
    val list = new util.ArrayList[Array[Integer]]()
    list.add(Array[Integer](0))
    list.add(Array[Integer](500000000))
    list
  }

  @BeforeClass
  def assertKerberosAuthentication(): Unit = {
    //assertNotNull(getClass.getResourceAsStream("/core-site.xml"))
  }

  @BeforeClass
  def setUpSpark(): Unit = {
    sc = {
      val config = new HdfsConfiguration
      config.set("hadoop.security.authentication", "kerberos")
      UserGroupInformation.setConfiguration(config)

      val conf = new SparkConf().set("spark.driver.bindAddress", "127.0.0.1")
      SparkUtils.createLocalSparkContext(sparkMaster = "local[*]", appName = getClass.getSimpleName, conf)
    }
  }

  @AfterClass
  def tearDownSpark(): Unit = {
    sc.stop()
  }

  private val polygon1 =
    """
      |{
      |  "type": "Polygon",
      |  "coordinates": [
      |    [
      |      [
      |        4.6770629938691854,
      |        50.82172692290532
      |      ],
      |      [
      |        4.6550903376191854,
      |        50.80697613242405
      |      ],
      |      [
      |        4.6866760309785604,
      |        50.797429020705295
      |      ],
      |      [
      |        4.7196350153535604,
      |        50.795692972629176
      |      ],
      |      [
      |        4.7402343805879354,
      |        50.81738893871384
      |      ],
      |      [
      |        4.6770629938691854,
      |        50.82172692290532
      |      ]
      |    ]
      |  ]
      |}
      """.stripMargin.parseGeoJson[Polygon]()

  private val polygon2 =
    """
      |{
      |  "type": "Polygon",
      |  "coordinates": [
      |    [
      |      [
      |        3.950237888725339,
      |        51.01001898590911
      |      ],
      |      [
      |        3.950237888725339,
      |        51.03442207171108
      |      ],
      |      [
      |        4.032635349662839,
      |        51.03442207171108
      |      ],
      |      [
      |        4.032635349662839,
      |        51.01001898590911
      |      ],
      |      [
      |        3.950237888725339,
      |        51.01001898590911
      |      ]
      |    ]
      |  ]
      |}
      """.stripMargin.parseGeoJson[Polygon]()

  //this polygon is also used in python tests with S2 data
  private val polygon3 =
    """
      |{
      |  "type": "Polygon",
      |  "coordinates": [
      |    [
      |            [7.022705078125007, 51.75432477678571],
      |            [7.659912109375007, 51.74333844866071],
      |            [7.659912109375007, 51.29289899553571],
      |            [7.044677734375007, 51.31487165178571],
      |            [7.022705078125007, 51.75432477678571]
      |    ]
      |  ]
      |}
      """.stripMargin.parseGeoJson[Polygon]()


  private val polygon4 =
    """
      |{
      |  "type": "Polygon",
      |  "coordinates": [[[5.608215013735893, 51.032165152086264], [5.608271547800293, 51.03221788952301], [5.608276702058292, 51.03222293623101], [5.60849596808989, 51.032448502828444], [5.608716500868006, 51.032663757587436], [5.608825832560376, 51.032770514885485], [5.608945000622374, 51.032880067275485], [5.609034654329857, 51.032962466956384], [5.6108271092295565, 51.03200900180982], [5.6108243802523665, 51.032005046553195], [5.610765550277478, 51.031906904685506], [5.610730775512867, 51.03185731710986], [5.610728864439129, 51.03185450621938], [5.61068097777416, 51.031781806466725], [5.610648884176528, 51.0317343801373], [5.610647867072976, 51.03173285113396], [5.61056103219013, 51.031600028841], [5.610464592173136, 51.031620528338046], [5.610108166306372, 51.03169612090491], [5.610099694700648, 51.03169779497791], [5.6098587750999505, 51.031741960985435], [5.609583519929178, 51.03179242194306], [5.6095812925908355, 51.0317928220292], [5.609462795599489, 51.031813670192285], [5.609462557298256, 51.03181371202481], [5.6094265107381105, 51.0318200256364], [5.609420285041724, 51.03182105270213], [5.609335088450095, 51.03183424727698], [5.609264660589062, 51.03184521822665], [5.609258526965428, 51.03184611349288], [5.609158373400448, 51.03185975557609], [5.6091581558557895, 51.03185978513339], [5.609101330081307, 51.0318674864013], [5.6090929559728915, 51.031868511547074], [5.608913831137317, 51.031888110200775], [5.608906493782254, 51.031888830479645], [5.608726551315478, 51.03190448174909], [5.60871912778759, 51.031905044225965], [5.608609468745772, 51.0319121289672], [5.608538666998535, 51.031916736443236], [5.60851084566201, 51.03191739795041], [5.608487750205862, 51.0319169979628], [5.608215013735893, 51.032165152086264]]]
      |}
      """.stripMargin.parseGeoJson[Polygon]()
}

@RunWith(classOf[Parameterized])
class ComputeStatsGeotrellisAdapterTest(threshold:Int) {
  import ComputeStatsGeotrellisAdapterTest._



  @Before
  def setup():Unit = {
    System.setProperty("pixels.treshold","" + threshold)

  }


  private val computeStatsGeotrellisAdapter = new ComputeStatsGeotrellisAdapter(
    zookeepers = "epod-master1.vgt.vito.be:2181,epod-master2.vgt.vito.be:2181,epod-master3.vgt.vito.be:2181",
    accumuloInstanceName = "hdp-accumulo-instance"
  )

  @Test
  def compute_average_timeseries(): Unit = {
    val from = ZonedDateTime.of(LocalDate.of(2019, 1, 1), LocalTime.MIDNIGHT, ZoneOffset.UTC)
    val to = from plusWeeks 1

    val polygons = Seq(polygon1.toWKT(), polygon2.toWKT())

    val stats = computeStatsGeotrellisAdapter.compute_average_timeseries(
      "S1_GRD_SIGMA0_ASCENDING",
      polygons.asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format from,
      to_date = ISO_OFFSET_DATE_TIME format to,
      zoom = 14,
      band_index = 2
    ).asScala

    for ((date, means) <- stats) {
      println(s"$date: $means")
    }

    assertFalse(stats.isEmpty)

    val means = stats
      .flatMap { case (_, dailyMeans) => dailyMeans.asScala }

    assertTrue(means.exists(mean => !mean.isNaN))
  }

  @Test
  def compute_average_timeseries_on_datacube(): Unit = {

    val minDate = ZonedDateTime.parse("2017-01-01T00:00:00Z")
    val maxDate = ZonedDateTime.parse("2017-03-10T00:00:00Z")

    val polygons = Seq(polygon1.toWKT(), polygon2.toWKT())

    val tile10 = new ByteConstantTile(10.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    val tile5 = new ByteConstantTile(5.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    //val datacube = TileLayerRDDBuilders.createMultibandTileLayerRDD(SparkContext.getOrCreate, new ArrayMultibandTile(Array[Tile](tile10, tile5)), new TileLayout(1, 1, 256, 256))

    val datacube = TestOpenEOProcesses.tileToSpaceTimeDataCube(tile10)
    val polygonExtent = polygon1.envelope.combine(polygon2.envelope)
    val updatedMetadata = datacube.metadata.copy(extent = polygonExtent,crs = LatLng,layout=LayoutDefinition(polygonExtent,datacube.metadata.tileLayout))

    val stats = computeStatsGeotrellisAdapter.compute_average_timeseries_from_datacube(
      ContextRDD(datacube,updatedMetadata),
      polygons.asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format minDate,
      to_date = ISO_OFFSET_DATE_TIME format maxDate,
      band_index = 0
    ).asScala

    for ((date, means) <- stats) {
      println(s"$date: $means")
    }

    assertFalse(stats.isEmpty)

    val means = stats
      .flatMap { case (_, dailyMeans) => dailyMeans.asScala }.filter(!_.isEmpty)

    assertTrue(means.exists(mean => !mean.get(0).isNaN))
  }


  private lazy val accumuloPyramidFactory = {new PyramidFactory("hdp-accumulo-instance", "epod-master1.vgt.vito.be:2181,epod-master2.vgt.vito.be:2181,epod-master3.vgt.vito.be:2181")}


  /**
    * This test is similar in setup to the openeo integrationtest:
    * test_validate_timeseries.Test#test_zonal_statistics
    *
    * But it is easier to debug.
    *
    * Testing against accumulo is necessary, because we have a custom RangePartitioner for performance, that affects the result if not implemented correctly
    */
  @Test
  def compute_median_timeseries_on_accumulo_datacube(): Unit = {

    val minDateString = "2017-11-01T00:00:00Z"
    val maxDateString = "2017-11-16T02:00:00Z"
    val minDate = ZonedDateTime.parse(minDateString)
    val maxDate = ZonedDateTime.parse(maxDateString)

    val polygons = Seq(polygon3)

    //bbox = new Extent(10.5, 46.5, 11.4, 46.9);
    //srs = "EPSG:4326";


    val datacube = accumuloDataCube("S2_FAPAR_PYRAMID_20190708", minDateString, maxDateString,  polygons.envelope,  "EPSG:4326")

    assertMedianComputedCorrectly(datacube, minDateString, minDate, maxDate, polygons)
  }

  private def accumuloDataCube(layer: String, minDateString: String, maxDateString: String, bbox: Extent, srs: String) = {
    val pyramid: Seq[(Int, RDD[(SpaceTimeKey, MultibandTile)] with Metadata[TileLayerMetadata[SpaceTimeKey]])] = accumuloPyramidFactory.pyramid_seq(layer, bbox, srs, minDateString, maxDateString)
    System.out.println("pyramid = " + pyramid)

    val pyramidAsMap = pyramid.toMap
    val maxZoom = pyramidAsMap.keys.max
    val datacube = pyramidAsMap.get(maxZoom).get
    datacube
  }

  @Test
  def compute_median_ndvi_timeseries_on_accumulo_datacube(): Unit = {

    val minDateString = "2017-11-01T00:00:00Z"
    val maxDateString = "2017-11-16T02:00:00Z"
    val minDate = ZonedDateTime.parse(minDateString)

    val maxDate = ZonedDateTime.parse(maxDateString)

    val polygons = Seq(polygon3)
    val datacube= accumuloDataCube("CGS_SENTINEL2_RADIOMETRY_V102_EARLY", minDateString, maxDateString, polygons.envelope, "EPSG:4326")

    val selectedBands = datacube.withContext(_.mapValues(_.subsetBands(1,3)))
    val ndviProcess = TestOpenEOProcessScriptBuilder.createNormalizedDifferenceProcess
    val ndviDataCube = new OpenEOProcesses().mapBandsGeneric(selectedBands, ndviProcess)

    assertMedianComputedCorrectly(ndviDataCube, minDateString, minDate, maxDate, polygons)
  }

  @Test
  def compute_median_masked_ndvi_timeseries_on_accumulo_datacube(): Unit = {

    val minDateString = "2018-04-21T00:00:00Z"
    val maxDateString = "2018-04-21T02:00:00Z"
    val minDate = ZonedDateTime.parse(minDateString)

    val maxDate = ZonedDateTime.parse(maxDateString)

    val polygons = Seq(polygon4)
    val datacube= accumuloDataCube("CGS_SENTINEL2_RADIOMETRY_V102_EARLY", minDateString, maxDateString, polygons.envelope, "EPSG:4326")

    val selectedBands = datacube.withContext(_.mapValues(_.subsetBands(1,3))).convert(DoubleConstantNoDataCellType)
    val ndviProcess = TestOpenEOProcessScriptBuilder.createNormalizedDifferenceProcess
    val processes = new OpenEOProcesses()
    val ndviDataCube = processes.mapBandsGeneric(selectedBands, ndviProcess)//.withContext(_.mapValues(_.mapDouble(0)(pixel => 0.1)))

    val mask = accumuloDataCube("S2_SCENECLASSIFICATION_PYRAMID_20190624", minDateString, maxDateString, polygons.envelope, "EPSG:4326")
    val binaryMask = mask.withContext(_.mapValues( _.map(0)(pixel => if ( pixel < 5) 1 else 0)))

    print(binaryMask.partitioner)

    val maskedCube = processes.rasterMask(ndviDataCube,binaryMask,Double.NaN)
    assertMedianComputedCorrectly(maskedCube, minDateString, minDate, maxDate, polygons)
  }

  private def assertMedianComputedCorrectly(ndviDataCube: RDD[(SpaceTimeKey, MultibandTile)] with Metadata[TileLayerMetadata[SpaceTimeKey]], minDateString: String, minDate: ZonedDateTime, maxDate: ZonedDateTime, polygons: Seq[Polygon]) = {
    //alternative way to compute a histogram that works for this simple case
    val histogram: Histogram[Double] = ndviDataCube.toSpatial(minDate).mask(polygons.map(_.reproject(LatLng, WebMercator))).histogram(1000)(0)
    print("MEDIAN: ")
    val expectedMedian = histogram.median()
    print(expectedMedian)

    val stats = computeStatsGeotrellisAdapter.compute_median_time_series_from_datacube(
      ndviDataCube,
      polygons.map(_.toWKT()).asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format minDate,
      to_date = ISO_OFFSET_DATE_TIME format maxDate,
      band_index = 0
    ).asScala

    for ((date, means) <- stats) {
      println(s"$date: $means")
    }

    assertFalse(stats.isEmpty)

    val means = stats
      .flatMap { case (_, dailyMeans) => dailyMeans.asScala }.filter(!_.isEmpty)

    assertTrue(means.exists(mean => !mean.get(0).isNaN))
    assertEquals( expectedMedian.get,stats(minDateString).get(0).get(0), 0.1)
  }

  @Test
  def compute_histogram_timeseries_on_datacube(): Unit = {

    val minDate = ZonedDateTime.parse("2017-01-01T00:00:00Z")
    val maxDate = ZonedDateTime.parse("2017-03-10T00:00:00Z")

    val polygons = Seq(polygon1.toWKT(), polygon2.toWKT())

    val tile10 = new ByteConstantTile(10.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    val tile5 = new ByteConstantTile(5.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    //val datacube = TileLayerRDDBuilders.createMultibandTileLayerRDD(SparkContext.getOrCreate, new ArrayMultibandTile(Array[Tile](tile10, tile5)), new TileLayout(1, 1, 256, 256))

    val datacube = TestOpenEOProcesses.tileToSpaceTimeDataCube(tile10)
    val polygonExtent = polygon1.envelope.combine(polygon2.envelope)
    val updatedMetadata = datacube.metadata.copy(extent = polygonExtent,crs = LatLng,layout=LayoutDefinition(polygonExtent,datacube.metadata.tileLayout))

    val stats = computeStatsGeotrellisAdapter.compute_histograms_time_series_from_datacube(
      ContextRDD(datacube,updatedMetadata),
      polygons.asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format minDate,
      to_date = ISO_OFFSET_DATE_TIME format maxDate,
      band_index = 0
    ).asScala

    for ((date, means) <- stats) {
      println(s"$date: $means")
    }

    assertFalse(stats.isEmpty)

    val histogramlist = stats.get("2017-01-01T00:00:00Z")
    val histogramPoly1 = histogramlist.get.get(0)
    assertEquals(507,histogramPoly1.get(0).get(10.0),0.01)

    //assertTrue(means.exists(mean => !mean.get(0).isNaN))
  }

  @Test
  def compute_median_timeseries_on_datacube(): Unit = {

    val minDate = ZonedDateTime.parse("2017-01-01T00:00:00Z")
    val maxDate = ZonedDateTime.parse("2017-03-10T00:00:00Z")

    val polygons = Seq(polygon1.toWKT(), polygon2.toWKT())

    val tile10 = new ByteConstantTile(10.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    val tile5 = new ByteConstantTile(5.toByte, 256, 256, ByteCells.withNoData(Some(255.byteValue())))
    //val datacube = TileLayerRDDBuilders.createMultibandTileLayerRDD(SparkContext.getOrCreate, new ArrayMultibandTile(Array[Tile](tile10, tile5)), new TileLayout(1, 1, 256, 256))

    val datacube = TestOpenEOProcesses.tileToSpaceTimeDataCube(tile10)
    val polygonExtent = polygon1.envelope.combine(polygon2.envelope)
    val updatedMetadata = datacube.metadata.copy(extent = polygonExtent,crs = LatLng,layout=LayoutDefinition(polygonExtent,datacube.metadata.tileLayout))

    val stats = computeStatsGeotrellisAdapter.compute_median_time_series_from_datacube(
      ContextRDD(datacube,updatedMetadata),
      polygons.asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format minDate,
      to_date = ISO_OFFSET_DATE_TIME format maxDate,
      band_index = 0
    ).asScala

    for ((date, medians) <- stats) {
      println(s"$date: $medians")
    }

    assertFalse(stats.isEmpty)

    val histogramlist = stats.get("2017-01-01T00:00:00Z")
    val histogramPoly1 = histogramlist.get.get(0)
    assertEquals(10.0,histogramPoly1.get(0),0.01)

    //assertTrue(means.exists(mean => !mean.get(0).isNaN))
  }

  @Test
  def compute_histograms_time_series(): Unit = {
    val from = ZonedDateTime.of(LocalDate.of(2019, 1, 1), LocalTime.MIDNIGHT, ZoneOffset.UTC)
    val to = from plusWeeks 1

    val polygons = Seq(polygon1.toWKT(), polygon2.toWKT())

    val histograms = computeStatsGeotrellisAdapter.compute_histograms_time_series(
      "S1_GRD_SIGMA0_ASCENDING",
      polygons.asJava,
      polygons_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format from,
      to_date = ISO_OFFSET_DATE_TIME format to,
      zoom = 14,
      band_index = 2
    ).asScala

    for ((date, polygonalHistograms) <- histograms) {
      println(s"$date: $polygonalHistograms")
    }

    assertFalse(histograms.isEmpty)

    val buckets = for {
      (_, polygonalHistograms) <- histograms.toSeq
      histogram <- polygonalHistograms.asScala
      bucket <- histogram.asScala
    } yield bucket

    assertFalse(buckets.isEmpty)
  }

  @Test
  def compute_histogram_time_series(): Unit = {
    val from = ZonedDateTime.of(LocalDate.of(2019, 1, 1), LocalTime.MIDNIGHT, ZoneOffset.UTC)
    val to = from plusWeeks 1

    val polygon = polygon1.toWKT()

    val histograms = computeStatsGeotrellisAdapter.compute_histogram_time_series(
      "S1_GRD_SIGMA0_ASCENDING",
      polygon,
      polygon_srs = "EPSG:4326",
      from_date = ISO_OFFSET_DATE_TIME format from,
      to_date = ISO_OFFSET_DATE_TIME format to,
      zoom = 14,
      band_index = 2
    ).asScala

    for ((date, polygonalHistogram) <- histograms) {
      println(s"$date: $polygonalHistogram")
    }

    assertFalse(histograms.isEmpty)

    val buckets = for {
      (_, polygonalHistogram) <- histograms
      bucket <- polygonalHistogram.asScala
    } yield bucket

    assertFalse(buckets.isEmpty)
  }
}

package org.openeo.geotrellis.file

import java.lang.Math.max
import java.net.URI
import java.time.ZonedDateTime

import geotrellis.layer._
import geotrellis.proj4.{CRS, WebMercator}
import geotrellis.raster.geotiff.GeoTiffRasterSource
import geotrellis.raster.io.geotiff.tags.TiffTags
import geotrellis.raster.{MultibandTile, Tile, UByteUserDefinedNoDataCellType}
import geotrellis.spark.partition.SpacePartitioner
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.store.hadoop.geotiff.{GeoTiffMetadata, InMemoryGeoTiffAttributeStore}
import geotrellis.spark.{ContextRDD, MultibandTileLayerRDD}
import geotrellis.store.hadoop.util.{HdfsRangeReader, HdfsUtils}
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.openeo.geotrellis.OpenEOProcesses
import org.openeo.geotrellis.file.Sentinel1CoherencePyramidFactory.Sentinel1Bands._
import org.openeo.geotrellis.file.Sentinel1CoherencePyramidFactory._

import scala.collection.JavaConverters._

object Sentinel1CoherencePyramidFactory {
  private val maxZoom = 14

  object Sentinel1Bands {
    val allBands: Seq[Sentinel1Band] = Seq(VV, VH)

    sealed trait Sentinel1Band
    case object VV extends Sentinel1Band
    case object VH extends Sentinel1Band
  }

  private object FileIMGeoTiffAttributeStore {
    def apply(name: String, path: Path): InMemoryGeoTiffAttributeStore =
      new InMemoryGeoTiffAttributeStore {
        override val metadataList: List[GeoTiffMetadata] = {
          val conf = new Configuration

          HdfsUtils
            .listFiles(path, conf)
            .map { p =>
              val tiffTags = TiffTags.read(HdfsRangeReader(p, conf))
              GeoTiffMetadata(tiffTags.extent, tiffTags.crs, name, p.toUri)
            }
        }

        override def persist(uri: URI): Unit = throw new UnsupportedOperationException
      }
  }

  private type FilePathTemplate = String => String

  private def overlappingFilePathTemplates(at: ZonedDateTime, bbox: ProjectedExtent): Iterable[FilePathTemplate] = {
    val (year, month, day) = (at.getYear, at.getMonthValue, at.getDayOfMonth)

    val vvGlob = new Path(f"file:/data/MTDA_DEV/CGS_S1/CGS_S1_SLC_COHERENCE/$year/$month%02d/$day%02d/*/*_VV.tif")

    val attributeStore = FileIMGeoTiffAttributeStore(at.toString, vvGlob)

    def pathTemplate(uri: URI): FilePathTemplate = bandId => uri.toString.replace("_VV.tif", s"_$bandId.tif")

    attributeStore.query(bbox).map(md => pathTemplate(md.uri))
  }

  private def correspondingBandFiles(pathTemplate: FilePathTemplate, bands: Seq[Sentinel1Band]): Seq[String] =
    bands.map(_.toString).map(pathTemplate)
}

class Sentinel1CoherencePyramidFactory {
  private def sequentialDates(from: ZonedDateTime): Stream[ZonedDateTime] = from #:: sequentialDates(from plusDays 1)

  def layer(boundingBox: ProjectedExtent, from: ZonedDateTime, to: ZonedDateTime, zoom: Int = maxZoom, bands: Seq[Sentinel1Band] = allBands)(implicit sc: SparkContext): MultibandTileLayerRDD[SpaceTimeKey] = {
    require(zoom >= 0)
    require(zoom <= maxZoom)

    val targetCrs: CRS = WebMercator
    val reprojectedBoundingBox = boundingBox.reproject(targetCrs)

    val layout = ZoomedLayoutScheme(targetCrs).levelForZoom(targetCrs.worldExtent, zoom).layout

    val dates = sequentialDates(from)
      .takeWhile(date => !(date isAfter to))

    val overlappingKeys = layout.mapTransform.keysForGeometry(reprojectedBoundingBox.extent.toPolygon())
    val overlappingFilesPerDay = sc.parallelize(dates, dates.length)
      .cartesian(sc.parallelize(overlappingKeys.toSeq, max(1, overlappingKeys.size / 20)))
      .map { case (date, spatialKey) =>
        (SpaceTimeKey(spatialKey, date), overlappingFilePathTemplates(date, ProjectedExtent(spatialKey.extent(layout), targetCrs))) }
      .filter(_._2.nonEmpty)

    val gridBounds = layout.mapTransform.extentToBounds(reprojectedBoundingBox.extent)
    val rddBounds = KeyBounds(SpaceTimeKey(gridBounds.colMin, gridBounds.rowMin, from), SpaceTimeKey(gridBounds.colMax, gridBounds.rowMax, to))

    val partitioned = overlappingFilesPerDay.partitionBy(SpacePartitioner(rddBounds))
    val tilesRdd: RDD[(SpaceTimeKey, MultibandTile)] = new OpenEOProcesses().applySpacePartitioner(partitioned.flatMap { case (key, overlappingFiles) =>
      val overlappingMultibandTiles: Iterable[MultibandTile] = overlappingFiles.flatMap(overlappingFile => {
        val bandTileSources = correspondingBandFiles(overlappingFile, bands)
          .map(bandFile => GeoTiffRasterSource(bandFile).reproject(targetCrs).tileToLayout(layout))

        val multibandTilesPerFile: Seq[Option[MultibandTile]] = bandTileSources.map(_.read(key.spatialKey))
        val singleTile = multibandTilesPerFile.filter(_.isDefined).foldLeft[Vector[Tile]](Vector[Tile]())(_ ++ _.get.bands)

        if (singleTile.nonEmpty) {
          Some(MultibandTile(singleTile))
        } else {
          Option.empty[MultibandTile]
        }
      })

      overlappingMultibandTiles.reduceOption(_ merge _).map((key, _))
    }, rddBounds)

    val metadata: TileLayerMetadata[SpaceTimeKey] = {
      val gridBounds = layout.mapTransform.extentToBounds(reprojectedBoundingBox.extent)

      TileLayerMetadata(
        cellType = UByteUserDefinedNoDataCellType(0),
        layout = layout,
        extent = reprojectedBoundingBox.extent,
        crs = targetCrs,
        KeyBounds(SpaceTimeKey(gridBounds.colMin, gridBounds.rowMin, from), SpaceTimeKey(gridBounds.colMax, gridBounds.rowMax, to))
      )
    }

    ContextRDD(tilesRdd, metadata)
  }

  def pyramid(boundingBox: ProjectedExtent, from: ZonedDateTime, to: ZonedDateTime, bands: Seq[Sentinel1Band] = allBands)(implicit sc: SparkContext): Pyramid[SpaceTimeKey, MultibandTile, TileLayerMetadata[SpaceTimeKey]] = {
    val layers = for (zoom <- maxZoom to maxZoom by -1) yield zoom -> layer(boundingBox, from, to, zoom, bands)
    Pyramid(layers.toMap)
  }

  def pyramid_seq(bbox: Extent, bbox_srs: String, from_date: String, to_date: String, band_indices: java.util.List[Int]): Seq[(Int, MultibandTileLayerRDD[SpaceTimeKey])] = {
    implicit val sc: SparkContext = SparkContext.getOrCreate()

    val projectedExtent = ProjectedExtent(bbox, CRS.fromName(bbox_srs))
    val from = ZonedDateTime.parse(from_date)
    val to = ZonedDateTime.parse(to_date)

    val bands: Seq[Sentinel1Band] =
      if (band_indices == null || band_indices.isEmpty) allBands
      else band_indices.asScala.map(allBands(_))

    pyramid(projectedExtent, from, to, bands).levels.toSeq
      .sortBy { case (zoom, _) => zoom }
      .reverse
  }
}

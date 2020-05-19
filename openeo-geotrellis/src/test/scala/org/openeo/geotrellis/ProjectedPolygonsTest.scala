package org.openeo.geotrellis

import geotrellis.proj4.CRS
import geotrellis.vector._
import org.junit.Assert._
import org.junit.Test
import org.openeo.geotrellis.ComputeStatsGeotrellisAdapterTest.{polygon1, polygon2}

import scala.collection.JavaConverters._

class ProjectedPolygonsTest() {

  @Test
  def projected_polygons_from_wkt(): Unit = {
    val pp = ProjectedPolygons.fromWkt(List(polygon1.toWKT()).asJava, "EPSG:4326")
    assertEquals(1, pp.polygons.length)
    assertEquals(MultiPolygon(polygon1), pp.polygons(0))
    assertEquals(CRS.fromEpsgCode(4326), pp.crs)
  }

  @Test
  def projected_polygons_from_vector_file(): Unit = {
    val pp = ProjectedPolygons.fromVectorFile(getClass.getResource("/org/openeo/geotrellis/GeometryCollection.json").getPath)
    assertEquals(2, pp.polygons.length)
    assertEquals(MultiPolygon(polygon1), pp.polygons(0))
    assertEquals(MultiPolygon(polygon2), pp.polygons(1))
    assertEquals(CRS.fromEpsgCode(4326), pp.crs)
  }

}
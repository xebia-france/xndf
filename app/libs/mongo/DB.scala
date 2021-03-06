package libs.mongo

import com.novus.salat.dao.SalatDAO
import se.radley.plugin.salat._
import play.api.{Play, Application, PlayException}
import play.api.Play.current
import models.mongoContext._
import com.mongodb.casbah.MongoCollection
import play.api.PlayException
import com.mongodb.casbah.gridfs.GridFS


class DB [ObjectType <: AnyRef : Manifest, ID <: Any : Manifest] {
  def withDao[A](collectionName:String)(block: SalatDAO[ObjectType,ID] => A): A = {
    val dao = new SalatDAO[ObjectType,ID](collection = mongoCollection(collectionName)) {}
    block(dao)
  }
}


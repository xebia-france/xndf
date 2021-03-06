package models

import java.util.Date
import com.mongodb.casbah.Imports._
import org.bson.types.ObjectId
import models.mongoContext._
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.json.Json._
import libs.json._
import com.novus.salat.annotations.raw.Salat
import com.novus.salat.dao.{SalatDAO, ModelCompanion}
import se.radley.plugin.salat._
import play.api.libs.json.JsString
import play.api.libs.json.JsObject
import play.api.libs.json.JsNumber
import play.api.mvc.MultipartFormData.FilePart
import play.api.libs.Files.TemporaryFile
import com.mongodb.casbah.gridfs.{GridFSDBFile, GridFSInputFile}
import play.api.Logger
import java.io.{ByteArrayOutputStream}


@Salat
sealed trait ExpenseStatusValues
case class Draft() extends ExpenseStatusValues
case class Submitted() extends ExpenseStatusValues

object ExpenseStatus {
  lazy val DRAFT:ExpenseStatusValues=Draft()
  lazy val SUBMITTED:ExpenseStatusValues=Submitted()
  implicit object ExpenseStatusJson extends Format[ExpenseStatusValues] {
    def reads(value: JsValue): ExpenseStatusValues = {
      value.as[String] match {
        case "Draft()" => Draft()
        case "Submitted()" => Submitted()
      }
    }

    def writes(status: ExpenseStatusValues) = {
      toJson(status.toString)
    }
  }
}
import ExpenseStatus._
case class ExpenseReport(id: ObjectId, from: Date, to: Date, userId: ObjectId, _lines: Seq[ExpenseLine],status:Option[ExpenseStatusValues]) {
  def subtotals:Seq[Expense]={
    val expensesByQualifier: Map[String, Seq[Expense]] = lines.map(_.expense).groupBy(_.qualifier)
    val subtotalsByQualifier=expensesByQualifier.map({
      case (qualifier, expenses) => (qualifier, expenses.map(_.amount).foldLeft(0.0)(_ + _))
    })
    subtotalsByQualifier.map(Expense.tupleToExpense).toSeq
  }

  lazy val lines = _lines

  def addLine(valueDate: Date, account: String, description: String, expense: Expense, evidences:Seq[ObjectId]) = {
    lazy val newParent: ExpenseReport = ExpenseReport(this.id, this.from, this.to, this.userId, line +: this.lines, this.status)
    lazy val line: ExpenseLine = ExpenseLine(valueDate, account, description, expense,evidences)
    newParent
  }

  def total = {
    lines.map(l => l.expense.amount).sum
  }

  def save() {
    ExpenseReport.save(this)
  }
}

case class ExpenseLine(valueDate: Date,
                       account: String,
                       description: String,
                       expense: Expense,
                       evidences:Seq[ObjectId]) {
}

object Evidence {
  import libs.mongo._
  def save(part: FilePart[TemporaryFile]): Option[ObjectId] = {
    val newFile: GridFSInputFile = gridFS("default").createFile(part.ref.file)
    newFile.filename=part.filename
    part.contentType.map(contentType => newFile.contentType = contentType)
    newFile.save()
    newFile._id
  }
  def findById(id:ObjectId): FilePart[Array[Byte]] ={
    val file: GridFSDBFile = gridFS("default").find(id)
    Logger.info(file.filename.toString +" "+file.size)
    val byteStream = new ByteArrayOutputStream()
    file.writeTo(byteStream)
    FilePart(file.filename.getOrElse("file"), file.filename.getOrElse(""),file.contentType,byteStream.toByteArray)
  }
}

object ExpenseLine {

  implicit object ExpenseLineFormat extends Format[ExpenseLine] {
    def reads(value: JsValue): ExpenseLine = {
      ExpenseLine(
        (value \ "valueDate").as[Date],
        (value \ "account").as[String],
        (value \ "description").as[String],
        ((value \ "expenseType").as[String], (value \ "expense").as[Double]),
        (value \ "evidences").as[Seq[String]].map({new ObjectId(_)})
      )
    }

    def writes(line: ExpenseLine) = {
      JsObject(
        Seq(
          "valueDate" -> toJson(line.valueDate),
          "account" -> JsString(line.account),
          "description" -> JsString(line.description),
          "expense" -> JsNumber(line.expense.amount),
          "expenseType" -> JsString(line.expense.qualifier),
          "evidences" -> JsArray(line.evidences.map(x => JsString(x.toString())))
        )
      )
    }
  }

}

object ExpenseReport extends ModelCompanion[ExpenseReport, ObjectId] {

  def dao={
    new SalatDAO[ExpenseReport,ObjectId](collection = mongoCollection("expenses")) {}
  }

  def findAllByUserId(userId: ObjectId): List[ExpenseReport] = {
    find(MongoDBObject("userId"->userId)).sort(MongoDBObject("from" -> -1, "to"-> -1)).toList
  }

  def findByIdAndUserID(id: ObjectId, userId: ObjectId): Option[ExpenseReport] = {
    findOne(MongoDBObject("userId" -> userId,"_id" -> id))
  }

  def findById(id: ObjectId): Option[ExpenseReport] = {
    findOneById(id)
  }

}

object ExpenseFormat {

  implicit object ExpenseReportWrites extends Writes[ExpenseReport] {
    def writes(report: ExpenseReport) = {
      toJson(
        Map(
          "id" -> toJson(report.id.toString),
          "userId" -> toJson(report.userId.toString),
          "startDate" -> toJson(report.from),
          "endDate" -> toJson(report.to),
          "total" -> toJson(report.total),
          "lines" -> toJson(report.lines) ,
          "status" -> toJson(report.status)
        )
      )
    }
  }

  implicit object ExpenseReportReads extends Reads[User => ExpenseReport] {
    def reads(json: JsValue) = { user =>
      val expenseReportId: ObjectId = (json \ "id").asOpt[String].map(new ObjectId(_)).getOrElse(new ObjectId())
      ExpenseReport(
        expenseReportId,
        (json \ "startDate").as[Date],
        (json \ "endDate").as[Date],
        user.id,
        (json \ "lines").as[Seq[ExpenseLine]],
        (json \ "status").asOpt[ExpenseStatusValues].orElse(Some(ExpenseStatus.DRAFT))
      )
    }
  }
}

object Expense {

  implicit def tupleToExpense(tuple: (String, Double)): Expense = {
    val (qualifier, amount) = tuple
    qualifier match {
      case "Lodging" => Lodging(amount)
      case "Transportation" => Transportation(amount)
      case "Gas" => Gas(amount)
      case "Meal" => Meal(amount)
      case "Phone" => Phone(amount)
      case "Internet" => Internet(amount)
      case "Other" => Other(amount)
    }
  }

}

@Salat
sealed trait Expense {
  val amount: Double
  val qualifier: String
}

case class Lodging(amount: Double) extends Expense {
  val qualifier = "Lodging"
}

case class Transportation(amount: Double) extends Expense {
  val qualifier = "Transportation"
}

case class Gas(amount: Double) extends Expense {
  val qualifier = "Gas"
}

case class Meal(amount: Double) extends Expense {
  val qualifier = "Meal"
}

case class Phone(amount: Double) extends Expense {
  val qualifier = "Phone"
}

case class Internet(amount: Double) extends Expense {
  val qualifier = "Internet"
}

case class Other(amount: Double) extends Expense {
  val qualifier = "Other"
}


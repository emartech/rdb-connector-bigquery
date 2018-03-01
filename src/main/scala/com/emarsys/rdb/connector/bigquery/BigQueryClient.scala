package com.emarsys.rdb.connector.bigquery

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.emarsys.rdb.connector.bigquery.BigQueryClient.DryRunJsonProtocol.DryRunResponse
import com.emarsys.rdb.connector.bigquery.BigQueryClient.QueryJsonProtocol.{QueryRequest, QueryResponse}
import com.emarsys.rdb.connector.bigquery.BigQueryClient.TableDataQueryJsonProtocol.TableDataQueryResponse
import com.emarsys.rdb.connector.bigquery.BigQueryClient.TableListQueryJsonProtocol.TableListQueryResponse
import com.emarsys.rdb.connector.bigquery.GoogleApi.{fieldListUrl, queryUrl, tableListUrl}
import com.emarsys.rdb.connector.bigquery.stream.BigQueryStreamSource
import com.emarsys.rdb.connector.common.ConnectorResponse
import com.emarsys.rdb.connector.common.models.Errors.{ErrorWithMessage, TableNotFound}
import com.emarsys.rdb.connector.common.models.TableSchemaDescriptors.{FieldModel, TableModel}
import spray.json._
import cats.syntax.option._

import scala.concurrent.ExecutionContext
import scala.util.Try

class BigQueryClient(val googleTokenActor: ActorRef, projectId: String, dataset: String)(
  implicit timeout: Timeout,
  materializer: ActorMaterializer
) {

  implicit val system: ActorSystem  = materializer.system
  implicit val ec: ExecutionContext = system.dispatcher

  def streamingQuery(query: String, dryRun: Boolean = false): Source[Seq[String], NotUsed] = {
    val request = createQueryRequest(query, dryRun)

    if (dryRun) {
      createDryQuerySource(request)
    } else {
      createQuerySource(request)
    }
  }

  def listTables(): ConnectorResponse[Seq[TableModel]] = {
    runMetaQuery(tableListUrl(projectId, dataset), parseTableResult)
  }

  def listFields(tableName: String): ConnectorResponse[Seq[FieldModel]] = {
    runMetaQuery(fieldListUrl(projectId, dataset, tableName), parseFieldResults).map {
      case Left(ErrorWithMessage(message)) if message.startsWith("Unexpected error in response: 404 Not Found") =>
        Left(TableNotFound(tableName))
      case other => other
    }
  }

  private def createQuerySource(request: HttpRequest) =
    BigQueryStreamSource(request, parseQueryResult, googleTokenActor, Http()).via(concatWitFieldNamesAsFirst)

  private def createDryQuerySource(request: HttpRequest) =
    BigQueryStreamSource(request, parseDryResult, googleTokenActor, Http()).mapConcat(identity)

  private def createQueryRequest(query: String, dryRun: Boolean) =
    HttpRequest(HttpMethods.POST, queryUrl(projectId), entity = createQueryBody(query, dryRun))

  private def createQueryBody(query: String, dryRun: Boolean) =
    HttpEntity(ContentTypes.`application/json`, QueryRequest(query, dryRun = Some(dryRun)).toJson.compactPrint)

  protected def runMetaQuery[T](url: String, parser: JsObject => Option[Seq[T]]): ConnectorResponse[Seq[T]] = {
    val request        = HttpRequest(HttpMethods.GET, url)
    val bigQuerySource = BigQueryStreamSource(request, parser, googleTokenActor, Http())

    bigQuerySource
      .runWith(Sink.seq)
      .map(listOfList => Right(listOfList.flatten))
      .recover { case ex: Throwable => Left(ErrorWithMessage(ex.getMessage)) }
  }

  private def parseQueryResult(result: JsObject): Option[(Seq[String], Seq[Seq[String]])] = {
    Try { result.convertTo[QueryResponse] }.map { queryResponse =>
      val fields = queryResponse.schema.fields.map(_.name)
      val rows   = queryResponse.rows.fold(Seq[Seq[String]]())(rowSeq => rowSeq.map(row => row.f.map(_.v)))

      (fields, rows)
    }.toOption
  }

  private def parseDryResult(result: JsObject): Option[List[Seq[String]]] = {
    val queryResponse = result.convertTo[DryRunResponse]

    val fields = Seq("totalBytesProcessed", "jobComplete", "cacheHit")
    val row =
      Seq(queryResponse.totalBytesProcessed, queryResponse.jobComplete.toString, queryResponse.cacheHit.toString)

    List(fields, row).some
  }

  private def parseTableResult(result: JsObject): Option[Seq[TableModel]] = {
    result.convertTo[TableListQueryResponse].toTableModelList.some
  }

  private def parseFieldResults(result: JsObject): Option[Seq[FieldModel]] = {
    result.convertTo[TableDataQueryResponse].toFieldModelList.some
  }

  private val concatWitFieldNamesAsFirst =
    Flow[(Seq[String], Seq[Seq[String]])].statefulMapConcat(() => {
      var isFirstRun = true

      {
        case (fields, rows) =>
          if (isFirstRun && rows.nonEmpty) {
            isFirstRun = false
            fields :: rows.toList
          } else {
            rows.toList
          }
      }
    })
}

object BigQueryClient {

  def apply(googleTokenActor: ActorRef,
            projectId: String,
            dataset: String)(implicit timeout: Timeout, materializer: ActorMaterializer): BigQueryClient =
    new BigQueryClient(googleTokenActor, projectId, dataset)

  object DryRunJsonProtocol extends DefaultJsonProtocol {

    case class DryRunResponse(totalBytesProcessed: String, jobComplete: Boolean, cacheHit: Boolean)

    implicit val dryRunFormat: JsonFormat[DryRunResponse] = jsonFormat3(DryRunResponse)
  }

  object QueryJsonProtocol extends DefaultJsonProtocol {

    case class QueryRequest(query: String,
                            useLegacySql: Boolean = false,
                            maxResults: Option[Int] = None,
                            dryRun: Option[Boolean] = None)

    case class QueryResponse(schema: Schema, rows: Option[Seq[Row]])
    case class Schema(fields: Seq[FieldSchema])
    case class FieldSchema(name: String)
    case class Row(f: Seq[RowValue])
    case class RowValue(v: String)

    implicit val queryRequestFormat: JsonFormat[QueryRequest] = jsonFormat4(QueryRequest)

    implicit val rowValueFormat: JsonFormat[RowValue] = new JsonFormat[RowValue] {
      override def write(obj: RowValue): JsValue = JsObject("v" -> JsString(obj.v))

      override def read(json: JsValue): RowValue = json.asJsObject.getFields("v") match {
        case Seq(JsString(value)) =>
          if (value == "true") RowValue("1")
          else if (value == "false") RowValue("0")
          else RowValue(value)
        case Seq(JsNull)       => RowValue(null)
        case Seq(JsBoolean(b)) => RowValue(if (b) "1" else "0")
        case Seq(value)        => RowValue(value.toString)
        case _                 => RowValue(json.toString)
      }
    }
    implicit val rowFormat: JsonFormat[Row]                     = jsonFormat1(Row)
    implicit val fieldFormat: JsonFormat[FieldSchema]           = jsonFormat1(FieldSchema)
    implicit val schemaFormat: JsonFormat[Schema]               = jsonFormat1(Schema)
    implicit val queryResponseFormat: JsonFormat[QueryResponse] = jsonFormat2(QueryResponse)

  }

  object TableListQueryJsonProtocol extends DefaultJsonProtocol {

    case class TableListQueryResponse(tables: Seq[QueryTableModel]) {
      def toTableModelList: Seq[TableModel] = {
        tables.map(_.toTableModel)
      }
    }

    case class QueryTableModel(tableReference: TableReference, `type`: String) {
      def toTableModel: TableModel = {
        TableModel(tableReference.tableId, `type` == "VIEW")
      }
    }

    case class TableReference(tableId: String)

    implicit val tableReferenceFormat: JsonFormat[TableReference]                 = jsonFormat1(TableReference)
    implicit val queryTableModelFormat: JsonFormat[QueryTableModel]               = jsonFormat2(QueryTableModel)
    implicit val tableListQueryResponseFormat: JsonFormat[TableListQueryResponse] = jsonFormat1(TableListQueryResponse)
  }

  object TableDataQueryJsonProtocol extends DefaultJsonProtocol {

    case class TableDataQueryResponse(schema: TableSchema) {
      def toFieldModelList = {
        schema.fields.map(_.toFieldModel)
      }
    }

    case class TableSchema(fields: Seq[Field])

    case class Field(name: String, `type`: String) {
      def toFieldModel = {
        FieldModel(name, `type`)
      }
    }

    implicit val fieldFormat: JsonFormat[Field]                                   = jsonFormat2(Field)
    implicit val tableSchemaFormat: JsonFormat[TableSchema]                       = jsonFormat1(TableSchema)
    implicit val tableDataQueryResponseFormat: JsonFormat[TableDataQueryResponse] = jsonFormat1(TableDataQueryResponse)
  }

}
package controllers

import akka.stream.scaladsl.{Flow, Sink, Source}
import com.google.cloud.bigquery.{FieldValueList, LegacySQLTypeName, Schema}
import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json._
import play.api.mvc.{InjectedController, WebSocket}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class Application @Inject() extends InjectedController with Logging {

  def index = Action { implicit request =>
    Ok(views.html.index(routes.Application.ws().webSocketURL()))
  }

  implicit val fieldValueListWrites = new Writes[(Schema, FieldValueList)] {
    override def writes(o: (Schema, FieldValueList)): JsValue = {
      val (schema, fieldValueList) = o

      schema.getFields.asScala.foldLeft(JsObject.empty) { case (json, field) =>
        val fieldValue = fieldValueList.get(field.getName)
        val fieldValueJson = field.getType match {
          case LegacySQLTypeName.BOOLEAN => Try(fieldValue.getBooleanValue).map(JsBoolean).getOrElse(JsNull)
          case LegacySQLTypeName.STRING => Try(fieldValue.getStringValue).map(JsString).getOrElse(JsNull)
          case LegacySQLTypeName.INTEGER => Try(fieldValue.getLongValue).map(JsNumber(_)).getOrElse(JsNull)
          case LegacySQLTypeName.FLOAT => Try(fieldValue.getDoubleValue).map(JsNumber(_)).getOrElse(JsNull)
            // add to the num as a terrible workaround to prevent the .0 from being stripped due to
            // play-json_2.13-2.7.4.jar!/play/api/libs/json/jackson/JacksonJson.class#64
          case LegacySQLTypeName.NUMERIC => Try(fieldValue.getNumericValue).map(_.add(BigDecimal(0.0001).bigDecimal)).map(JsNumber(_)).getOrElse(JsNull)
          case _ => JsNull
        }

        json + (field.getName -> fieldValueJson)
      }
    }
  }

  def ws = WebSocket.acceptOrResult[JsValue, JsValue] { _ =>
    val query = """
                  |SELECT bike_id, CAST(start_station_id AS STRING) start_station_id, CAST(end_station_id AS STRING) end_station_id, CAST(ts AS NUMERIC) ts,
                  |       duration, prcp, temp, CAST(day_of_week AS STRING) day_of_week, euclidean, loc_cross, max, min, dewp
                  |FROM `aju-vtests2.london_bikes_weather.view2_table2`
                  |ORDER BY ts
                  |LIMIT 100000
                  |""".stripMargin

    StackOverflowBigQuery.query(query).fold({ t =>
      logger.error("Query error", t)
      Future.successful(Left(InternalServerError(t.getMessage)))
    }, { case (schema, questions) =>
      val questionSource = Source.fromIterator(() => questions.iterator)
      val source = Source.tick(Duration.Zero, 1.second, schema).zip(questionSource).map(Json.toJson(_))
      Future.successful(Right(Flow.fromSinkAndSource(Sink.ignore, source)))
    })
  }

}

object StackOverflowBigQuery {
  import java.util.UUID

  import com.google.cloud.bigquery.{BigQuery, BigQueryOptions, FieldValueList, JobId, JobInfo, QueryJobConfiguration}

  def query(query: String): Try[(Schema, Iterable[FieldValueList])] = {
    Try {
      val bigQuery = BigQueryOptions.getDefaultInstance.getService
      val jobId = JobId.of(UUID.randomUUID().toString)
      val queryConfig = QueryJobConfiguration.newBuilder(query).setUseLegacySql(false).build()
      val queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())
      queryJob.waitFor()
      val page = queryJob.getQueryResults(BigQuery.QueryResultsOption.pageSize(1))
      page.getSchema -> page.iterateAll().asScala
    }
  }

}

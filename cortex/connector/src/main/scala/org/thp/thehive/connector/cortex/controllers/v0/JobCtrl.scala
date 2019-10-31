package org.thp.thehive.connector.cortex.controllers.v0

import scala.concurrent.{ExecutionContext, Future}
import play.api.Logger
import play.api.mvc.{Action, AnyContent, Results}
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.{AuthorizationError, ErrorHandler}
import org.thp.scalligraph.controllers.{EntryPoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.{ParamQuery, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.connector.cortex.controllers.v0.Conversion._
import org.thp.thehive.connector.cortex.models.Job
import org.thp.thehive.connector.cortex.services.{JobSrv, JobSteps}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.controllers.v0.{IdOrName, OutputParam, QueryableCtrl}
import org.thp.thehive.services.ObservableSrv
import org.thp.thehive.models.Permissions

@Singleton
class JobCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    properties: Properties,
    jobSrv: JobSrv,
    observableSrv: ObservableSrv,
    errorHandler: ErrorHandler,
    implicit val ec: ExecutionContext
) extends QueryableCtrl {
  lazy val logger                                           = Logger(getClass)
  override val entityName: String                           = "job"
  override val publicProperties: List[PublicProperty[_, _]] = properties.job
  override val initialQuery: Query =
    Query.init[JobSteps]("listJob", (graph, authContext) => jobSrv.initSteps(graph).visible(authContext))
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, JobSteps](
    "getJob",
    FieldsParser[IdOrName],
    (param, graph, authContext) => jobSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, JobSteps, PagedResult[Job with Entity]](
    "page",
    FieldsParser[OutputParam],
    (range, jobSteps, _) => jobSteps.page(range.from, range.to, withTotal = true)
  )
  override val outputQuery: Query = Query.output[Job with Entity]()

  def get(jobId: String): Action[AnyContent] =
    entryPoint("get job")
      .authRoTransaction(db) { implicit request => implicit graph =>
        jobSrv
          .getByIds(jobId)
          .visible
          .getOrFail()
          .map { job =>
            Results.Ok(job.toJson)
          }
      }

  def create: Action[AnyContent] =
    entryPoint("create job")
      .extract("analyzerId", FieldsParser[String].on("analyzerId"))
      .extract("cortexId", FieldsParser[String].on("cortexId"))
      .extract("artifactId", FieldsParser[String].on("artifactId"))
      .asyncAuth { implicit request =>
        if (request.permissions.contains(Permissions.manageAnalyse)) {
          val analyzerId: String = request.body("analyzerId")
          val cortexId: String   = request.body("cortexId")
          db.roTransaction { implicit graph =>
            val artifactId: String = request.body("artifactId")
            for {
              o <- observableSrv.getByIds(artifactId).richObservable.getOrFail()
              c <- observableSrv.getByIds(artifactId).`case`.getOrFail()
            } yield (o, c)
          }
            .fold(error => errorHandler.onServerError(request, error), {
              case (o, c) =>
                jobSrv
                  .submit(cortexId, analyzerId, o, c)
                  .map(j => Results.Created(j.toJson))
            })
        } else Future.failed(AuthorizationError("Job creation not allowed"))
      }
}

package com.twitter.mesos.scheduler;

import java.util.Set;
import java.util.logging.Logger;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

import com.twitter.common.collections.Pair;
import com.twitter.common_internal.elfowl.Cookie;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.gen.CreateJobResponse;
import com.twitter.mesos.gen.FinishUpdateResponse;
import com.twitter.mesos.gen.Identity;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.KillResponse;
import com.twitter.mesos.gen.MesosSchedulerManager;
import com.twitter.mesos.gen.ResponseCode;
import com.twitter.mesos.gen.RestartResponse;
import com.twitter.mesos.gen.RollbackShardsResponse;
import com.twitter.mesos.gen.UpdateResponseCode;
import com.twitter.mesos.gen.ScheduleStatusResponse;
import com.twitter.mesos.gen.SessionKey;
import com.twitter.mesos.gen.StartCronResponse;
import com.twitter.mesos.gen.StartUpdateResponse;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.gen.UpdateResult;
import com.twitter.mesos.gen.UpdateShardsResponse;
import com.twitter.mesos.scheduler.SchedulerCore.RestartException;
import com.twitter.mesos.scheduler.configuration.ConfigurationManager;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.twitter.common.base.MorePreconditions.checkNotBlank;
import static com.twitter.mesos.gen.ResponseCode.AUTH_FAILED;
import static com.twitter.mesos.gen.ResponseCode.INVALID_REQUEST;
import static com.twitter.mesos.gen.ResponseCode.OK;

/**
 * Mesos scheduler thrift server implementation.
 * Interfaces between mesos users and the scheduler core to perform cluster administration tasks.
 *
 * @author William Farner
 */
public class SchedulerThriftInterface implements MesosSchedulerManager.Iface {
  private static final Logger LOG = Logger.getLogger(SchedulerThriftInterface.class.getName());

  private final SchedulerCore schedulerCore;

  @Inject
  public SchedulerThriftInterface(SchedulerCore schedulerCore) {
    this.schedulerCore = checkNotNull(schedulerCore);
  }

  /**
   * Given a sessionKey, determine the response type and provide human-readable error message.
   */
  private Pair<ResponseCode, String> validateSessionKey(SessionKey sessionKey,
      String targetRole) {
    if (!sessionKey.isSetOwner()
        || !sessionKey.getOwner().isSetRole()
        || !sessionKey.getOwner().isSetUser()
        || !sessionKey.isSetCookie()) {
      return Pair.of(AUTH_FAILED, "Incorrectly specified session key.");
    }

    Cookie cookie = Cookie.fromBase64(sessionKey.getCookie());

    if (cookie == null) {
      return Pair.of(AUTH_FAILED, "Unable to parse supplied cookie.");
    }

    // Make sure the cookie is properly cryptographically signed by the correct user.
    if (!cookie.isVerified()) {
      return Pair.of(AUTH_FAILED, "Cookie appears to be forged.");
    }

    // The cookie identity and the session key identity must match for the session to
    // be valid.
    if (!cookie.getUser().equals(sessionKey.getOwner().getUser())) {
      return Pair.of(AUTH_FAILED,
          String.format("Supplied cookie and session identity are for different users (%s vs %s)",
              cookie.getUser(),
              sessionKey.getOwner().getUser()));
    }

    // We need to accept this session based upon a targetRole.  The targetRole is going to be
    // one of two cases:
    //   - the username -- not an explicit ODS group but an accepted role
    //       (this will be made explicit when we have the usermap)
    //   - the ODS group that the user has choosed as the role
    if (!cookie.getUser().equals(targetRole) && !cookie.hasGroup(targetRole)) {
      return Pair.of(AUTH_FAILED,
          String.format("User %s does not have permission for role %s",
              cookie.getUser(), targetRole));
    }

    return Pair.of(OK, "");
  }

  @Override
  public CreateJobResponse createJob(JobConfiguration job, SessionKey session) {
    checkNotNull(job);
    checkNotNull(session, "Session must be set.");

    LOG.info("Received createJob request: " + Tasks.jobKey(job));
    CreateJobResponse response = new CreateJobResponse();

    Pair<ResponseCode, String> rc = validateSessionKey(session, job.getOwner().getRole());
    if (rc.getFirst() != OK) {
      response.setResponseCode(rc.getFirst()).setMessage(rc.getSecond());
      return response;
    }

    try {
      schedulerCore.createJob(job);
      response.setResponseCode(OK)
          .setMessage(String.format("%d new tasks pending for job %s",
              job.getTaskConfigs().size(), Tasks.jobKey(job)));
    } catch (ConfigurationManager.TaskDescriptionException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Invalid task description: " + e.getMessage());
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Failed to schedule job - " + e.getMessage());
    }

    return response;
  }

  @Override
  public StartCronResponse startCronJob(String jobName, SessionKey session) {
    checkNotBlank(jobName, "Job name must not be blank.");
    checkNotNull(session, "Session must be provided.");

    StartCronResponse response = new StartCronResponse();
    Pair<ResponseCode, String> rc = validateSessionKey(session, session.getOwner().getRole());

    if (rc.getFirst() != OK) {
      response.setResponseCode(rc.getFirst()).setMessage(rc.getSecond());
      return response;
    }

    try {
      schedulerCore.startCronJob(session.getOwner().getRole(), jobName);
      response.setResponseCode(OK).setMessage("Cron run started.");
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Failed to start cron job - " + e.getMessage());
    }

    return response;
  }

  // TODO(William Farner): Provide status information about cron jobs here.
  @Override
  public ScheduleStatusResponse getTasksStatus(TaskQuery query) {
    checkNotNull(query);

    Set<TaskState> tasks = schedulerCore.getTasks(new Query(query));

    ScheduleStatusResponse response = new ScheduleStatusResponse();
    if (tasks.isEmpty()) {
      response.setResponseCode(INVALID_REQUEST).setMessage("No tasks found for query: " + query);
    } else {
      response.setResponseCode(OK)
          .setTasks(Lists.newArrayList(Iterables.transform(tasks, TaskState.STATE_TO_LIVE)));
    }

    return response;
  }

  @Override
  public KillResponse killTasks(TaskQuery query, SessionKey session) {
    checkNotNull(query);
    checkNotNull(session, "Session must be set.");

    LOG.info("Received kill request for tasks: " + query);
    KillResponse response = new KillResponse();

    Set<TaskState> tasks = schedulerCore.getTasks(new Query(query));
    String sessionRole = session.getOwner().getRole();
    for (TaskState task : tasks) {
      Identity taskId = task.task.getAssignedTask().getTask().getOwner();
      if (!sessionRole.equals(taskId.getRole()) && !sessionRole.equals(taskId.getUser())) {
        response.setResponseCode(AUTH_FAILED).setMessage(
            "You do not have permission to kill all tasks in this query.");
        return response;
      }
    }

    Pair<ResponseCode, String> rc = validateSessionKey(session, sessionRole);
    if (rc.getFirst() != OK) {
      response.setResponseCode(rc.getFirst()).setMessage(rc.getSecond());
      return response;
    }

    try {
      schedulerCore.killTasks(new Query(query));
      response.setResponseCode(OK).setMessage("Tasks will be killed.");
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  // TODO(William Farner); This should address a job/shard and not task IDs, and should check to
  //     ensure that the shards requested exist (reporting failure and ignoring request otherwise).
  @Override
  public RestartResponse restartTasks(Set<String> taskIds, SessionKey session) {
    checkNotBlank(taskIds, "At least one task ID must be provided.");
    checkNotNull(session, "Session must be set.");

    ResponseCode response = OK;
    String message = taskIds.size() + " tasks scheduled for restart.";

    // TODO(wickman): Enforce that taskIds are all a compatible role with the
    // session.
    Pair<ResponseCode, String> rc = validateSessionKey(session, session.getOwner().getRole());
    if (rc.getFirst() != OK) {
      return new RestartResponse(rc.getFirst(), rc.getSecond());
    }

    try {
      schedulerCore.restartTasks(taskIds);
    } catch (RestartException e) {
      response = INVALID_REQUEST;
      message = e.getMessage();
    }

    return new RestartResponse(response, message);
  }

  @Override
  public StartUpdateResponse startUpdate(JobConfiguration job, SessionKey session) {
    checkNotNull(job);
    checkNotNull(session, "Session must be set.");

    LOG.info("Received update request for tasks: " + Tasks.jobKey(job));
    StartUpdateResponse response = new StartUpdateResponse();
    Pair<ResponseCode, String> rc = validateSessionKey(session, job.getOwner().getRole());
    if (rc.getFirst() != OK) {
      return response.setResponseCode(rc.getFirst()).setMessage(rc.getSecond());
    }

    try {
      response.setUpdateToken(schedulerCore.startUpdate(job));
      response.setResponseCode(OK).setMessage("Update successfully started.");
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
    } catch (ConfigurationManager.TaskDescriptionException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public UpdateShardsResponse updateShards(String role, String jobName,
      Set<Integer> shards, String updateToken, SessionKey session) {
    checkNotBlank(role, "Role may not be blank.");
    checkNotBlank(jobName, "Job may not be blank.");
    checkNotBlank(shards, "At least one shard must be specified.");
    checkNotBlank(updateToken, "Update token may not be blank.");
    checkNotNull(session, "Session must be set.");

    UpdateShardsResponse response = new UpdateShardsResponse();
    try {
      schedulerCore.updateShards(role, jobName, shards, updateToken);
      response.setResponseCode(UpdateResponseCode.OK).
          setMessage("Successful update of shards: " + shards);
    } catch (ScheduleException e) {
      response.setResponseCode(UpdateResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public RollbackShardsResponse rollbackShards(String role, String jobName,
      Set<Integer> shards, String updateToken, SessionKey session) {
    checkNotBlank(role, "Role may not be blank.");
    checkNotBlank(jobName, "Job may not be blank.");
    checkNotBlank(shards, "At least one shard must be specified.");
    checkNotBlank(updateToken, "Update token may not be blank.");
    checkNotNull(session, "Session must be set.");

    RollbackShardsResponse response = new RollbackShardsResponse();
    try {
      schedulerCore.rollbackShards(role, jobName, shards, updateToken);
      response.setResponseCode(UpdateResponseCode.OK).
          setMessage("Successful rollback of shards: " + shards);
    } catch (ScheduleException e) {
      response.setResponseCode(UpdateResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public FinishUpdateResponse finishUpdate(String role, String jobName,
      UpdateResult updateResult, String updateToken, SessionKey session) {
    checkNotBlank(role, "Role may not be blank.");
    checkNotBlank(jobName, "Job may not be blank.");
    checkNotNull(session, "Session must be set.");

    FinishUpdateResponse response = new FinishUpdateResponse();
    try {
      schedulerCore.finishUpdate(role, jobName,
          updateResult == UpdateResult.TERMINATE ? null : updateToken, updateResult);
      response.setResponseCode(UpdateResponseCode.OK);
    } catch (ScheduleException e) {
      response.setResponseCode(UpdateResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }
}

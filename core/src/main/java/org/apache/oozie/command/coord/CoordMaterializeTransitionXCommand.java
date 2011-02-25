/**
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. See accompanying LICENSE file.
 */
package org.apache.oozie.command.coord;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.apache.hadoop.conf.Configuration;
import org.apache.oozie.CoordinatorActionBean;
import org.apache.oozie.CoordinatorJobBean;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.client.Job;
import org.apache.oozie.client.SLAEvent.SlaAppType;
import org.apache.oozie.command.CommandException;
import org.apache.oozie.command.MaterializeTransitionXCommand;
import org.apache.oozie.command.PreconditionException;
import org.apache.oozie.command.bundle.BundleStatusUpdateXCommand;
import org.apache.oozie.coord.TimeUnit;
import org.apache.oozie.executor.jpa.CoordActionInsertJPAExecutor;
import org.apache.oozie.executor.jpa.CoordJobGetJPAExecutor;
import org.apache.oozie.executor.jpa.CoordJobUpdateJPAExecutor;
import org.apache.oozie.executor.jpa.JPAExecutorException;
import org.apache.oozie.service.JPAService;
import org.apache.oozie.service.Services;
import org.apache.oozie.util.DateUtils;
import org.apache.oozie.util.Instrumentation;
import org.apache.oozie.util.LogUtils;
import org.apache.oozie.util.ParamChecker;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XLog;
import org.apache.oozie.util.XmlUtils;
import org.apache.oozie.util.db.SLADbOperations;
import org.jdom.Element;

/**
 * Materialize actions for specified start and end time for coordinator job.
 */
public class CoordMaterializeTransitionXCommand extends MaterializeTransitionXCommand {
    private static final int LOOKAHEAD_WINDOW = 300; // We look ahead 5 minutes for materialization;
    private final XLog LOG = XLog.getLog(CoordMaterializeTransitionXCommand.class);
    private JPAService jpaService = null;
    CoordinatorJobBean coordJob = null;
    private String jobId = null;
    private Date startMatdTime = null;
    private Date endMatdTime = null;
    private int materializationWindow;
    private int lastActionNumber = 1; // over-ride by DB value

    /**
     * The constructor for class {@link CoordMaterializeTransitionXCommand}
     *
     * @param jobId coordinator job id
     * @param startMatdTime materialization start time
     * @param endMatdTime materialization end time
     * @param materializationWindow materialization window to calculate end time
     */
    public CoordMaterializeTransitionXCommand(String jobId, Date startTime, Date endTime, int materializationWindow) {
        super("coord_mater", "coord_mater", 1);
        this.jobId = ParamChecker.notEmpty(jobId, "jobId");
        this.startMatdTime = startTime;
        this.endMatdTime = endTime;
        this.materializationWindow = materializationWindow;
    }

    /**
     * The constructor for class {@link CoordMaterializeTransitionXCommand}
     *
     * @param jobId coordinator job id
     * @param materializationWindow materialization window to calculate end time
     */
    public CoordMaterializeTransitionXCommand(String jobId, int materializationWindow) {
        super("coord_mater", "coord_mater", 1);
        this.jobId = ParamChecker.notEmpty(jobId, "jobId");
        this.materializationWindow = materializationWindow;
    }

    @Override
    public void notifyParent() throws CommandException {

    }

    @Override
    public void transitToNext() throws CommandException {

    }

    @Override
    public void updateJob() throws CommandException {
        try {
            jpaService.execute(new CoordJobUpdateJPAExecutor(coordJob));
        }
        catch (JPAExecutorException jex) {
            throw new CommandException(jex);
        }
    }

    @Override
    protected String getEntityKey() {
        return jobId;
    }

    @Override
    protected boolean isLockRequired() {
        return true;
    }

    @Override
    protected void loadState() throws CommandException {
        jpaService = Services.get().get(JPAService.class);
        if (jpaService == null) {
            LOG.error(ErrorCode.E0610);
        }

        try {
            coordJob = jpaService.execute(new CoordJobGetJPAExecutor(jobId));
        }
        catch (JPAExecutorException jex) {
            throw new CommandException(jex);
        }

        // calculate start materialize and end materialize time
        calcMatdTime();

        LogUtils.setLogInfo(coordJob, logInfo);
    }

    @Override
    protected void verifyPrecondition() throws CommandException, PreconditionException {
        if (!(coordJob.getStatus() == CoordinatorJobBean.Status.PREP || coordJob.getStatus() == CoordinatorJobBean.Status.RUNNING)) {
            throw new PreconditionException(ErrorCode.E1100, "CoordMaterializeTransitionXCommand for jobId=" + jobId
                    + " job is not in PREP or RUNNING but in " + coordJob.getStatus());
        }

        if (coordJob.getNextMaterializedTimestamp() != null
                && coordJob.getNextMaterializedTimestamp().compareTo(coordJob.getEndTimestamp()) >= 0) {
            throw new PreconditionException(ErrorCode.E1100, "CoordMaterializeTransitionXCommand for jobId=" + jobId
                    + " job is already materialized");
        }

        if (coordJob.getNextMaterializedTimestamp() != null
                && coordJob.getNextMaterializedTimestamp().compareTo(new Timestamp(System.currentTimeMillis())) >= 0) {
            throw new PreconditionException(ErrorCode.E1100, "CoordMaterializeTransitionXCommand for jobId=" + jobId
                    + " job is already materialized");
        }

        Timestamp startTime = coordJob.getNextMaterializedTimestamp();
        if (startTime == null) {
            startTime = coordJob.getStartTimestamp();

            if (startTime.after(new Timestamp(System.currentTimeMillis() + LOOKAHEAD_WINDOW * 1000))) {
                throw new PreconditionException(ErrorCode.E1100, "CoordMaterializeTransitionXCommand for jobId="
                        + jobId + " job's start time is not reached yet - nothing to materialize");
            }
        }

        if (coordJob.getLastActionTime() != null && coordJob.getLastActionTime().compareTo(endMatdTime) >= 0) {
            throw new PreconditionException(ErrorCode.E1100, "ENDED Coordinator materialization for jobId = " + jobId
                    + ", action is *already* materialized for Materialization start time = " + startMatdTime
                    + ", materialization end time = " + endMatdTime + ", job status = " + coordJob.getStatusStr());
        }

        if (endMatdTime.after(coordJob.getEndTime())) {
            throw new PreconditionException(ErrorCode.E1100, "ENDED Coordinator materialization for jobId = " + jobId
                    + " materialization end time = " + endMatdTime + " surpasses coordinator job's end time = "
                    + coordJob.getEndTime() + " job status = " + coordJob.getStatusStr());
        }

        if (coordJob.getPauseTime() != null && !startMatdTime.before(coordJob.getPauseTime())) {
            // pausetime blocks real materialization - we change job's status back to RUNNING;
            coordJob.setStatus(Job.Status.PAUSED);
            coordJob.setLastModifiedTime(new Date());

            try {
                jpaService.execute(new CoordJobUpdateJPAExecutor(coordJob));
            }
            catch (JPAExecutorException ex) {
                throw new CommandException(ex);
            }

            throw new PreconditionException(ErrorCode.E1100, "ENDED Coordinator materialization for jobId = " + jobId
                    + ", materialization start time = " + startMatdTime
                    + " is after or equal to coordinator job's pause time = " + coordJob.getPauseTime()
                    + ", job status = " + coordJob.getStatusStr());
        }

    }

    @Override
    public void materialize() throws CommandException {
        Instrumentation.Cron cron = new Instrumentation.Cron();
        cron.start();
        try {
            materializeActions(false);
            updateJobTable(coordJob);
        }
        catch (CommandException ex) {
            LOG.warn("Exception occurs:" + ex + " Making the job failed ");
            coordJob.setStatus(Job.Status.FAILED); // will update to db in updateJob()
        }
        catch (Exception e) {
            LOG.error("Excepion thrown :", e);
            throw new CommandException(ErrorCode.E1001, e.getMessage(), e);
        }
        cron.stop();

    }

    protected void calcMatdTime() throws CommandException {
        Timestamp startTime = coordJob.getNextMaterializedTimestamp();
        if (startTime == null) {
            startTime = coordJob.getStartTimestamp();
        }
        // calculate end time by adding materializationWindow to start time.
        // need to convert materializationWindow from secs to milliseconds
        long startTimeMilli = startTime.getTime();
        long endTimeMilli = startTimeMilli + (materializationWindow * 1000);

        startMatdTime = DateUtils.toDate(new Timestamp(startTimeMilli));
        endMatdTime = DateUtils.toDate(new Timestamp(endTimeMilli));
        // if MaterializationWindow end time is greater than endTime
        // for job, then set it to endTime of job
        Date jobEndTime = coordJob.getEndTime();
        if (endMatdTime.compareTo(jobEndTime) > 0) {
            endMatdTime = jobEndTime;
        }

        LOG.debug("Materializing coord job id=" + jobId + ", start=" + startMatdTime + ", end=" + endMatdTime
                + ", window=" + materializationWindow);
    }

    /**
     * Create action instances starting from "startMatdTime" to "endMatdTime" and store them into coord action table.
     *
     * @param dryrun if this is a dry run
     * @throws Exception thrown if failed to materialize actions
     */
    protected String materializeActions(boolean dryrun) throws Exception {

        Configuration jobConf = null;
        try {
            jobConf = new XConfiguration(new StringReader(coordJob.getConf()));
        }
        catch (IOException ioe) {
            LOG.warn("Configuration parse error. read from DB :" + coordJob.getConf(), ioe);
            throw new CommandException(ErrorCode.E1005, ioe);
        }

        String jobXml = coordJob.getJobXml();
        Element eJob = XmlUtils.parseXml(jobXml);
        TimeZone appTz = DateUtils.getTimeZone(coordJob.getTimeZone());
        int frequency = coordJob.getFrequency();
        TimeUnit freqTU = TimeUnit.valueOf(eJob.getAttributeValue("freq_timeunit"));
        TimeUnit endOfFlag = TimeUnit.valueOf(eJob.getAttributeValue("end_of_duration"));
        Calendar start = Calendar.getInstance(appTz);
        start.setTime(startMatdTime);
        DateUtils.moveToEnd(start, endOfFlag);
        Calendar end = Calendar.getInstance(appTz);
        end.setTime(endMatdTime);
        lastActionNumber = coordJob.getLastActionNumber();
        LOG.info("materialize actions for tz=" + appTz.getDisplayName() + ",\n start=" + start.getTime() + ", end="
                + end.getTime() + ",\n timeUnit " + freqTU.getCalendarUnit() + ",\n frequency :" + frequency + ":"
                + freqTU + ",\n lastActionNumber " + lastActionNumber);
        // Keep the actual start time
        Calendar origStart = Calendar.getInstance(appTz);
        origStart.setTime(coordJob.getStartTimestamp());
        // Move to the End of duration, if needed.
        DateUtils.moveToEnd(origStart, endOfFlag);
        // Cloning the start time to be used in loop iteration
        Calendar effStart = (Calendar) origStart.clone();
        // Move the time when the previous action finished
        effStart.add(freqTU.getCalendarUnit(), lastActionNumber * frequency);

        StringBuilder actionStrings = new StringBuilder();
        Date jobPauseTime = coordJob.getPauseTime();
        Calendar pause = null;
        if (jobPauseTime != null) {
            pause = Calendar.getInstance(appTz);
            pause.setTime(DateUtils.convertDateToTimestamp(jobPauseTime));
        }

        String action = null;
        while (effStart.compareTo(end) < 0) {
            if (pause != null && effStart.compareTo(pause) >= 0) {
                break;
            }
            CoordinatorActionBean actionBean = new CoordinatorActionBean();
            lastActionNumber++;

            int timeout = coordJob.getTimeout();
            LOG.debug("Materializing action for time=" + effStart.getTime() + ", lastactionnumber=" + lastActionNumber);
            action = CoordCommandUtils.materializeOneInstance(jobId, dryrun, (Element) eJob.clone(),
                    effStart.getTime(), lastActionNumber, jobConf, actionBean);

            int catchUpTOMultiplier = 1; // This value might be could be changed in future
            if (actionBean.getNominalTimestamp().before(coordJob.getCreatedTimestamp())) {
                // catchup action
                timeout = catchUpTOMultiplier * timeout;
                // actionBean.setTimeOut(Services.get().getConf().getInt(CONF_DEFAULT_TIMEOUT_CATCHUP, -1));
                LOG.info("Catchup timeout is :" + actionBean.getTimeOut());
            }
            actionBean.setTimeOut(timeout);
            if (!dryrun) {
                storeToDB(actionBean, action); // Storing to table
            }
            else {
                actionStrings.append("action for new instance");
                actionStrings.append(action);
            }
            // Restore the original start time
            effStart = (Calendar) origStart.clone();
            effStart.add(freqTU.getCalendarUnit(), lastActionNumber * frequency);
        }

        endMatdTime = new Date(effStart.getTimeInMillis());
        if (!dryrun) {
            return action;
        }
        else {
            return actionStrings.toString();
        }
    }

    private void storeToDB(CoordinatorActionBean actionBean, String actionXml) throws Exception {
        LOG.debug("In storeToDB() coord action id = " + actionBean.getId() + ", size of actionXml = "
                + actionXml.length());
        actionBean.setActionXml(actionXml);

        jpaService.execute(new CoordActionInsertJPAExecutor(actionBean));
        writeActionRegistration(actionXml, actionBean);

        // TODO: time 100s should be configurable
        queue(new CoordActionNotificationXCommand(actionBean), 100);
        queue(new CoordActionInputCheckXCommand(actionBean.getId()), 100);
    }

    private void writeActionRegistration(String actionXml, CoordinatorActionBean actionBean) throws Exception {
        Element eAction = XmlUtils.parseXml(actionXml);
        Element eSla = eAction.getChild("action", eAction.getNamespace()).getChild("info", eAction.getNamespace("sla"));
        SLADbOperations.writeSlaRegistrationEvent(eSla, actionBean.getId(), SlaAppType.COORDINATOR_ACTION, coordJob
                .getUser(), coordJob.getGroup(), LOG);
    }

    private void updateJobTable(CoordinatorJobBean job) throws CommandException {
        job.setLastActionTime(endMatdTime);
        job.setLastActionNumber(lastActionNumber);
        // if the job endtime == action endtime, then set status of job to succeeded
        // we dont need to materialize this job anymore
        Date jobEndTime = job.getEndTime();
        if (jobEndTime.compareTo(endMatdTime) <= 0) {
            job.setStatus(Job.Status.SUCCEEDED);
            LOG.info("[" + job.getId() + "]: Update status from RUNNING to SUCCEEDED");

            // update bundle action
            if (job.getBundleId() != null) {
                BundleStatusUpdateXCommand bundleStatusUpdate = new BundleStatusUpdateXCommand(job,
                        Job.Status.RUNNING);
                bundleStatusUpdate.call();
            }
        }
        else {
            job.setStatus(Job.Status.RUNNING);
            LOG.info("[" + job.getId() + "]: Update status from RUNNING to RUNNING");
        }
        job.setNextMaterializedTime(endMatdTime);
        try {
            jpaService.execute(new CoordJobUpdateJPAExecutor(job));
        }
        catch (JPAExecutorException ex) {
            throw new CommandException(ex);
        }
    }

}

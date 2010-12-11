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
package org.apache.oozie.command.bundle;

import java.util.Date;
import java.util.List;

import org.apache.oozie.BundleActionBean;
import org.apache.oozie.BundleJobBean;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.XException;
import org.apache.oozie.client.Job;
import org.apache.oozie.command.CommandException;
import org.apache.oozie.command.PreconditionException;
import org.apache.oozie.command.TransitionXCommand;
import org.apache.oozie.command.coord.CoordSuspendXCommand;
import org.apache.oozie.command.jpa.BundleActionUpdateCommand;
import org.apache.oozie.command.jpa.BundleActionsGetCommand;
import org.apache.oozie.command.jpa.BundleJobGetCommand;
import org.apache.oozie.command.jpa.BundleJobUpdateCommand;
import org.apache.oozie.service.JPAService;
import org.apache.oozie.service.Services;
import org.apache.oozie.util.ParamChecker;
import org.apache.oozie.util.XLog;

public class BundleJobSuspendXCommand extends TransitionXCommand<Void>{
    private final String jobId;
    private JPAService jpaService;
    private List<BundleActionBean> bundleActions;
    private BundleJobBean bundleJob;
    private final XLog log = XLog.getLog(getClass());

    /**
     * @param id
     */
    public BundleJobSuspendXCommand(String id) {
        super("coord_suspend", "coord_suspend", 1);
        this.jobId = ParamChecker.notEmpty(id, "id");
    }

    @Override
    public Job getJob() {
        return null;
    }

    @Override
    public void notifyParent() throws CommandException {
    }

    @Override
    public void setJob(Job job) {
    }

    @Override
    public void transitToNext() throws CommandException {
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#execute()
     */
    @Override
    protected Void execute() throws CommandException {
        try {
            incrJobCounter(1);
            if(bundleJob.getStatus() == Job.Status.PREP){
                bundleJob.setStatus(Job.Status.PREPSUSPENDED);
            }
            else if(bundleJob.getStatus() == Job.Status.RUNNING){
                bundleJob.setStatus(Job.Status.SUSPENDED);
            }
            bundleJob.setPending();
            bundleJob.setSuspendedTime(new Date());

            for (BundleActionBean action : this.bundleActions) {
                if (action.getStatus() == Job.Status.RUNNING || action.getStatus() == Job.Status.PREP) {
                    // queue a SuspendCommand
                    if (action.getCoordId() != null) {
                        queue(new CoordSuspendXCommand(action.getCoordId()));
                        action.setPending(action.getPending()+1);
                        jpaService.execute(new BundleActionUpdateCommand(action));
                    }
                }
            }
            jpaService.execute(new BundleJobUpdateCommand(bundleJob));
            return null;
        }
        catch (XException ex) {
            throw new CommandException(ex);
        }
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#getEntityKey()
     */
    @Override
    protected String getEntityKey() {
        return this.jobId;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#isLockRequired()
     */
    @Override
    protected boolean isLockRequired() {
        return true;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#loadState()
     */
    @Override
    protected void loadState() throws CommandException {
        try{
            this.bundleActions = jpaService.execute(new BundleActionsGetCommand(jobId));
        }
        catch(Exception Ex){
            throw new CommandException(ErrorCode.E1311,this.jobId);
        }
    }

    @Override
    protected void verifyPrecondition() throws CommandException, PreconditionException {
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#eagerLoadState()
     */
    @Override
    protected void eagerLoadState() throws CommandException {

        try {
            jpaService = Services.get().get(JPAService.class);

            if (jpaService != null) {
                this.bundleJob = jpaService.execute(new BundleJobGetCommand(jobId));
                setLogInfo(bundleJob);
            }
            else {
                throw new CommandException(ErrorCode.E0610);
            }
        }
        catch (XException ex) {
            throw new CommandException(ex);
        }
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#eagerVerifyPrecondition()
     */
    @Override
    protected void eagerVerifyPrecondition() throws CommandException, PreconditionException {
        if (bundleJob.getStatus() == Job.Status.SUCCEEDED
                || bundleJob.getStatus() == Job.Status.FAILED) {
            log.info("BundleSuspendCommand not suspended - " + "job finished or does not exist " + jobId);
            throw new PreconditionException(ErrorCode.E1312,jobId,bundleJob.getStatus().toString());
        }
    }

    @Override
    public void updateJob() throws CommandException {
        // TODO Auto-generated method stub

    }
}

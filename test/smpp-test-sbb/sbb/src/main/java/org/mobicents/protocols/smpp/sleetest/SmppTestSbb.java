/**
 * TeleStax, Open Source Cloud Communications  Copyright 2012. 
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.smpp.sleetest;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.slee.ActivityContextInterface;
import javax.slee.CreateException;
import javax.slee.RolledBackContext;
import javax.slee.Sbb;
import javax.slee.SbbContext;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ResourceAdaptorTypeID;

import org.mobicents.slee.SbbContextExt;
import org.restcomm.slee.resource.smpp.SmppSessions;
import org.restcomm.slee.resource.smpp.SmppTransaction;
import org.restcomm.slee.resource.smpp.SmppTransactionACIFactory;
import org.restcomm.smpp.Esme;

import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;

/**
 *
 * @author sergey vetyutnev
 */
public abstract class SmppTestSbb implements Sbb {

    private final String loggerName = "ParentSbb";

    protected SbbContextExt sbbContext;
    protected Tracer logger;

    protected static final ResourceAdaptorTypeID mapRATypeID = new ResourceAdaptorTypeID("MAPResourceAdaptorType",
            "org.mobicents", "2.0");
    protected static final String mapRaLink = "MAPRA";
    private SmppTransactionACIFactory smppServerTransactionACIFactory = null;
    protected SmppSessions smppServerSessions = null;


    /** Creates a new instance of CallSbb */
    public SmppTestSbb() {
    }

    public void setSbbContext(SbbContext sbbContext) {
        this.sbbContext = (SbbContextExt) sbbContext;
        this.logger = sbbContext.getTracer(this.loggerName);

        try {
            Context ctx = (Context) new InitialContext().lookup("java:comp/env");

            this.smppServerTransactionACIFactory = (SmppTransactionACIFactory) ctx
                    .lookup("slee/resources/smppp/server/1.0/acifactory");
            this.smppServerSessions = (SmppSessions) ctx.lookup("slee/resources/smpp/server/1.0/provider");
        } catch (Exception ne) {
            logger.severe("Could not set SBB context:", ne);
        }
    }

    public void unsetSbbContext() {
        // clean RAs
        this.smppServerTransactionACIFactory = null;
        this.smppServerSessions = null;

        // clean SLEE
        this.sbbContext = null;
        this.logger = null;
    }

    public void sbbCreate() throws CreateException {
    }

    public void sbbPostCreate() throws CreateException {
    }

    public void sbbActivate() {
    }

    public void sbbPassivate() {
    }

    public void sbbLoad() {
    }

    public void sbbStore() {
    }

    public void sbbRemove() {
    }

    public void sbbExceptionThrown(Exception exception, Object object, ActivityContextInterface activityContextInterface) {
    }

    public void sbbRolledBack(RolledBackContext rolledBackContext) {
    }

    public void onSubmitSm(com.cloudhopper.smpp.pdu.SubmitSm event, ActivityContextInterface aci) {
        SmppTransaction smppServerTransaction = (SmppTransaction) aci.getActivity();
        Esme esme = smppServerTransaction.getEsme();

        this.logger.info("Tx SubmitSm " + event + ", esme=" + esme.getName());

        SubmitSmResp response = event.createResponse();
        response.setMessageId("00000001");
        try {
            this.smppServerSessions.sendResponsePdu(esme, event, response);
        } catch (RecoverablePduException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (UnrecoverablePduException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SmppChannelException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}

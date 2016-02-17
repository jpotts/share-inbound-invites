package com.metaversant.behaviors;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.Behaviour.NotificationFrequency;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;

import com.metaversant.inbound.invitation.InvitationProcessor;

public class OnEmailedNodeUpdate implements NodeServicePolicies.OnUpdateNodePolicy {

	// Dependencies
	private NodeService nodeService;
	private PolicyComponent policyComponent;
	private InvitationProcessor invitationProcessor;

	// Behaviours
	private Behaviour onUpdateNode;

	private Logger logger = Logger.getLogger(OnEmailedNodeUpdate.class);

	public void init() {
		if (logger.isDebugEnabled()) logger.debug("Initializing process invitation behavior");

		// Create behaviours
		this.onUpdateNode = new JavaBehaviour(this, "onUpdateNode", NotificationFrequency.TRANSACTION_COMMIT);

		// Bind behaviours to node policies
		this.policyComponent.bindClassBehaviour(
			QName.createQName(NamespaceService.ALFRESCO_URI, "onUpdateNode"),
			ContentModel.ASPECT_EMAILED,
			this.onUpdateNode
		);
	}

	@Override
	public void onUpdateNode(NodeRef nodeRef) {
		// if this node does not have the "emailed" aspect then there is no work to do
		if (!nodeService.hasAspect(nodeRef, ContentModel.ASPECT_EMAILED)) {
			return;
		}

		if (logger.isDebugEnabled()) logger.debug("Inside update node");

		invitationProcessor.processEmail(nodeRef);
	}

	// *******************
	// GETTERS AND SETTERS
	// *******************

	public NodeService getNodeService() {
		return nodeService;
	}

	public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}

	public PolicyComponent getPolicyComponent() {
		return policyComponent;
	}

	public void setPolicyComponent(PolicyComponent policyComponent) {
		this.policyComponent = policyComponent;
	}

	public InvitationProcessor getInvitationProcessor() {
		return invitationProcessor;
	}

	public void setInvitationProcessor(InvitationProcessor invitationProcessor) {
		this.invitationProcessor = invitationProcessor;
	}

}

package com.metaversant.inbound.behaviors;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.metaversant.inbound.common.InboundInvitesConstants;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.Behaviour.NotificationFrequency;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;

/**
 * This class binds to the updateNodePolicy for site folders. It is used to
 * make sure that sites have what we need to process inbound calendar invites.
 * 
 * @author jpotts, Metaversant
 */
public class OnSiteNodeUpdate implements NodeServicePolicies.OnUpdateNodePolicy {
	// Dependencies
	private NodeService nodeService;
	private PolicyComponent policyComponent;
	private SiteService siteService;

	// Behaviours
	private Behaviour onUpdateNode;

	// InboundInvitesConstants
	private final String INVITATIONS_COMPONENT_ID = "inboundInvitations";

	private Logger logger = Logger.getLogger(OnSiteNodeUpdate.class);

	public void init() {
		if (logger.isDebugEnabled()) logger.debug("Initializing site node update behavior");

		// Create behaviours
		this.onUpdateNode = new JavaBehaviour(this, "onUpdateNode", NotificationFrequency.TRANSACTION_COMMIT);

		// Bind behaviours to node policies
		this.policyComponent.bindClassBehaviour(
			QName.createQName(NamespaceService.ALFRESCO_URI, "onUpdateNode"),
			SiteModel.TYPE_SITE,
			this.onUpdateNode
		);
	}

	@Override
	public void onUpdateNode(NodeRef siteNodeRef) {
		if (!nodeService.exists(siteNodeRef)) {
			return;
		}

		// get the site we are currently sitting in
		String siteId = (String) nodeService.getProperty(siteNodeRef, ContentModel.PROP_NAME);

		// create a folder to hold the inbound invitations
		NodeRef invitationsFolder = siteService.getContainer(siteId, INVITATIONS_COMPONENT_ID);
		if (invitationsFolder == null) {
			if (logger.isDebugEnabled()) logger.debug("Invitations folder does not exist, attempting to create");
			Map<QName, Serializable> props = new HashMap<QName, Serializable>();
			props.put(ContentModel.PROP_NAME, InboundInvitesConstants.INVITATIONS_FOLDER_NAME);
			invitationsFolder = siteService.createContainer(
					siteId,
					INVITATIONS_COMPONENT_ID,
					ContentModel.TYPE_FOLDER,
					props
			);
		}

		// set an email alias on the folder equal to the site's ID
		Map<QName, Serializable> props = new HashMap<QName, Serializable>();
		props.put(QName.createQName(NamespaceService.EMAILSERVER_MODEL_URI, "alias"), siteId);
		nodeService.addAspect(invitationsFolder, QName.createQName(NamespaceService.EMAILSERVER_MODEL_URI, "aliasable"), props);
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

	public SiteService getSiteService() {
		return siteService;
	}

	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

}

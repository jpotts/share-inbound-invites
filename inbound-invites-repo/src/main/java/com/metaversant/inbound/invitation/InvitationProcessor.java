package com.metaversant.inbound.invitation;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.Version;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.calendar.CalendarModel;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.AssociationRef;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.ResultSetRow;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;

import com.metaversant.behaviors.OnEmailedNodeUpdate;

/**
 * This class is responsible for parsing calendar invitations sent as ICS files
 * and taking corresponding action in an Alfresco Share site calendar.
 * 
 * @author jpotts, Metaversant
 */
public class InvitationProcessor {

	// Dependencies
	private NodeService nodeService;
	private SiteService siteService;
	private ContentService contentService;
	private SearchService searchService;
	private FileFolderService fileFolderService;

	// Constants
	private final String INVITATIONS_FOLDER_NAME = "inboundInvitations";
	private final String PROCESSED_FOLDER_NAME = "processed";
	private static final String CALENDAR_COMPONENT_ID = "calendar";
	private static final String CALENDAR_FOLDER_NAME = "calendar";

	private Logger logger = Logger.getLogger(OnEmailedNodeUpdate.class);

	/**
	 * This method looks at node references that were created by the inbound
	 * SMTP process and looks for associated attachments that are ICS files.
	 * 
	 * When ICS files are found they are processed to update the calendar entry
	 * in the Share site, then the node ref and its associated attachments are
	 * moved to another folder.
	 * 
	 * @param emailNodeRef The node reference of the emailed object.
	 */
	public void processEmail(NodeRef emailNodeRef) {
		if (logger.isDebugEnabled()) logger.debug("Processing email");

		// this is an email. grab its attachments
		List<AssociationRef> attachments = nodeService.getTargetAssocs(emailNodeRef, ContentModel.ASSOC_ATTACHMENTS);

		// if it has no attachments, there is no work to do
		if (!(attachments == null) && !attachments.isEmpty()) {
			// noop
		} else {
			if (logger.isDebugEnabled()) logger.debug("Email has no attachments");
			return;
		}

		// check the folder that the invitation is sitting in. it needs to be the root
		// folder used for inbound invites, otherwise this may be a node that we've already
		// processed, and the behavior is triggering b/c the node was updated when it was
		// moved to the processed folder.
		ChildAssociationRef childAssoc = nodeService.getPrimaryParent(emailNodeRef); // inboundInvitations
		NodeRef parentFolder = childAssoc.getParentRef();
		String parentFolderName = (String) nodeService.getProperty(parentFolder, ContentModel.PROP_NAME);
		if (!parentFolderName.equals(INVITATIONS_FOLDER_NAME)) {
			if (logger.isDebugEnabled()) logger.debug("Invitation not sitting in the expected folder. Maybe it was already processed.");
			return;
		}

		// for every attachment
		// if there is an ICS file (mime type of "text/calendar", then process it
		for (AssociationRef assoc : attachments) {
			if (logger.isDebugEnabled()) logger.debug("Checking email attachment");
			NodeRef attachment = assoc.getTargetRef();
			ContentData contentData = (ContentData) nodeService.getProperty(attachment, ContentModel.PROP_CONTENT);
			String mimeType = contentData.getMimetype();
			if (mimeType.equals("text/calendar")) {
				if (logger.isDebugEnabled()) logger.debug("Found text/calendar");
				processCalendarInvite(emailNodeRef, attachment);
			} else {
				if (logger.isDebugEnabled()) logger.debug("Not text/calendar: " + mimeType);
			}
		}

		// move the invitation, its email, and any other attachments that
		// came with it to a processed folder
		NodeRef processedFolder = getProcessedFolder(emailNodeRef);

		// move the invite
		try {
			fileFolderService.move(emailNodeRef, processedFolder, null);
		} catch (FileExistsException | FileNotFoundException fe) {
			logger.error("Problem moving email to processed folder: " + emailNodeRef.getId());
			return;
		}

		// move each attachment
		for (AssociationRef assoc : attachments) {
			NodeRef attachment = assoc.getTargetRef();
			try {
				fileFolderService.move(attachment, processedFolder, null);
			} catch (FileExistsException | FileNotFoundException fe) {
				logger.error("Problem moving attachment to processed folder: " + attachment.getId());
				return;
			}
		}
	}

	/**
	 * This method actually parses the calendar invite and then takes the
	 * appropriate action in the calendar depending on the action.
	 * 
	 * @param inviteNodeRef The node reference of the ICS file.
	 */
	public void processCalendarInvite(NodeRef emailNodeRef, NodeRef inviteNodeRef) {
		if (logger.isDebugEnabled()) logger.debug("Processing calendar invite");

		// get the site we are currently sitting in
		String site = siteService.getSiteShortName(inviteNodeRef);
		if (site == null) {
			if (logger.isDebugEnabled()) logger.debug("Invitation is not in a Share site--no work to do");
			return;
		}

		// get the calendar folder for that site (it may not exist yet)
		NodeRef calFolder = getCalendarFolder(site);

		if (calFolder == null) {
			logger.error("Unable to get calendar folder for site: " + site);
			return;
		}

		// parse the ICS file
		CalendarInfo calInfo = null;
		try {
			calInfo = parseIcsFile(inviteNodeRef);
		} catch (Exception e) {
			logger.error("Caught exception while parsing ICS file: " + e.getMessage());
		}

		if (calInfo == null) {
			logger.error("Unable to parse ICS file for nodeRef: " + inviteNodeRef.getId());
			return;
		}

		// if the action is create
		if (calInfo.getAction().equals("CREATE")) {
			// create a new calendar entry in the calendar folder if one does
			// not exist for the same id, otherwise update
			createOrUpdateEvent(emailNodeRef, calFolder, calInfo);
		} else if (calInfo.getAction().equals("DELETE")) {
			// if the action is delete
			// find the current calendar entry and delete it if it exists
			NodeRef event = findEventForId(calFolder, calInfo.getId());
			if (event != null) {
				deleteEvent(event);
			}
		} else {
			// otherwise log an exception
			logger.error("Unexpected action: " + calInfo.getAction() + " for nodeRef: " + inviteNodeRef.getId());
		}

	}

	/**
	 * Given a folder that holds calendar objects, this method finds objects
	 * with a specified event ID.
	 * 
	 * @param folder Node reference of the folder holding calendar objects.
	 * @param id     Unique identifier of the invitation.
	 * @return Node reference for the matching event object.
	 */
	public NodeRef findEventForId(NodeRef folder, String id) {
		if (logger.isDebugEnabled()) logger.debug("Finding event");
		String queryString = "PARENT:\"workspace://SpacesStore/" + folder.getId() + "\" AND ia:outlookUID:\"" + id + "\"";
		if (logger.isDebugEnabled()) logger.debug("Query string: " + queryString);
		ResultSet results = null;
		NodeRef nodeRef = null;
		try {
			results = searchService.query(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, SearchService.LANGUAGE_FTS_ALFRESCO, queryString);
			for (ResultSetRow row : results) {
				nodeRef = row.getNodeRef();
				break;
			}
		} finally {
			if (results != null) {
				results.close();
			}
		}

		return nodeRef;
	}

	/**
	 * Deletes the specified event.
	 * 
	 * @param event Event to be deleted.
	 */
	public void deleteEvent(NodeRef event) {
		if (logger.isDebugEnabled()) logger.debug("Deleting event");
		nodeService.deleteNode(event);
	}

	/**
	 * If the event already exists, the event will be updated with the calendar
	 * info provided, otherwise a new event will be created.
	 * 
	 * @param folder  Node reference for the folder holding the calendar objects.
	 * @param calInfo POJO holding calendar metadata.
	 */
	public void createOrUpdateEvent(NodeRef emailNodeRef, NodeRef folder, CalendarInfo calInfo) {
		NodeRef existingEvent = findEventForId(folder, calInfo.getId());
		if (existingEvent == null) {
			createEvent(emailNodeRef, folder, calInfo);
		} else {
			updateEvent(existingEvent, calInfo);
		}
	}

	/**
	 * Create a new calendar object in the Alfresco Share site.
	 * 
	 * @param folder  Node reference for the folder holding the calendar objects.
	 * @param calInfo POJO holding calendar metadata.
	 */
	public void createEvent(NodeRef emailNodeRef, NodeRef folder, CalendarInfo calInfo) {
		if (logger.isDebugEnabled()) logger.debug("Creating event");

		// assign name
        String name = getEventName(calInfo);
        Map<QName, Serializable> props = getProperties(calInfo);
        props.put(ContentModel.PROP_NAME, name);

        ChildAssociationRef childAssoc = nodeService.createNode(
				folder,
                ContentModel.ASSOC_CONTAINS,
                QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, name),
                CalendarModel.TYPE_EVENT,
                props
        );

        // capture email props to store on the calendar entry
        NodeRef calObj = childAssoc.getChildRef();
        Map<QName, Serializable> emailProps = new HashMap<QName, Serializable>();
        emailProps.put(ContentModel.PROP_SENTDATE, nodeService.getProperty(emailNodeRef, ContentModel.PROP_SENTDATE));
        emailProps.put(ContentModel.PROP_ADDRESSEE, nodeService.getProperty(emailNodeRef, ContentModel.PROP_ADDRESSEE));
        emailProps.put(ContentModel.PROP_ADDRESSEES, nodeService.getProperty(emailNodeRef, ContentModel.PROP_ADDRESSEES));
        emailProps.put(ContentModel.PROP_ORIGINATOR, nodeService.getProperty(emailNodeRef, ContentModel.PROP_ORIGINATOR));
        nodeService.addAspect(calObj, ContentModel.ASPECT_EMAILED, emailProps);
	}

	/**
	 * Creates a unique name for the ICS attachment.
	 * 
	 * @param calInfo POJO holding calendar metadata.
	 * @return String with the unique name of the ICS attachment.
	 */
	public String getEventName(CalendarInfo calInfo) {
		return "emailed-event-" + calInfo.getId() + "-" + System.currentTimeMillis() + ".ics";
	}

	/**
	 * Builds a properties map using the data in the CalendarInfo POJO.
	 * 
	 * @param calInfo POJO holding calendar metadata.
	 * @return Map of properties suitable for setting on an Alfresco node.
	 */
	public Map<QName, Serializable> getProperties(CalendarInfo calInfo) {
        Map<QName, Serializable> props = new HashMap<QName, Serializable>();

        // add the props from the CalendarInfo object
        props.put(CalendarModel.PROP_WHAT, calInfo.getSummary());
        props.put(CalendarModel.PROP_DESCRIPTION, calInfo.getDescription());
        props.put(CalendarModel.PROP_FROM_DATE, calInfo.getStartDate());
        props.put(CalendarModel.PROP_TO_DATE, calInfo.getEndDate());
        props.put(CalendarModel.PROP_WHERE, calInfo.getLocation());
        props.put(CalendarModel.PROP_IS_OUTLOOK, false);
        props.put(CalendarModel.PROP_OUTLOOK_UID, calInfo.getId());

        return props;
	}

	/**
	 * Update an existing event with the information in the CalendarInfo
	 * object.
	 * 
	 * @param existingEvent Node reference for the existing event.
	 * @param calInfo       POJO holding calendar metadata.
	 */
	public void updateEvent(NodeRef existingEvent, CalendarInfo calInfo) {
		if (logger.isDebugEnabled()) logger.debug("Updating event");
		// get the current properties of the node
		Map<QName, Serializable> props = nodeService.getProperties(existingEvent);

		// get the new properties from the Calendar Info
		Map<QName, Serializable> updatedProps = getProperties(calInfo);

		// merge the props
		props.putAll(updatedProps);

		// update the properties on the node
		nodeService.setProperties(existingEvent, props);
	}

	/**
	 * Gets the calendar folder for a given Share site.
	 * 
	 * @param siteId The short name of the Share site.
	 * @return The node reference of the calendar folder.
	 */
	public NodeRef getCalendarFolder(String siteId) {
		NodeRef calendarFolder = siteService.getContainer(siteId, CALENDAR_COMPONENT_ID);
		if (calendarFolder == null) {
			if (logger.isDebugEnabled()) logger.debug("Calendar folder does not exist, attempting to create");
			Map<QName, Serializable> props = new HashMap<QName, Serializable>();
			props.put(ContentModel.PROP_NAME, CALENDAR_FOLDER_NAME);
			calendarFolder = siteService.createContainer(
					siteId,
					CALENDAR_COMPONENT_ID,
					QName.createQName("http://www.alfresco.org/model/calendar", CALENDAR_FOLDER_NAME),
					props
			);
		}
		return calendarFolder;
	}

	/**
	 * Parses an ICS file and turns it into a CalendarInfo object.
	 * 
	 * @param nodeRef Node reference containing the ICS file.
	 * @return POJO holding calendar metadata.
	 * @throws Exception if the calendar method is something other than
	 *         request or cancel or if the UID for the invite cannot be
	 *         determined.
	 */
	public CalendarInfo parseIcsFile(NodeRef nodeRef) throws Exception {
		if (logger.isDebugEnabled()) logger.debug("Parsing ICS file");

		CalendarInfo calInfo = new CalendarInfo();
		InputStream contentStream = null;
    	try {
    		ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
    		contentStream = reader.getContentInputStream();

    		CalendarBuilder builder = new CalendarBuilder();
	    	Calendar calendar = builder.build(contentStream);
	    	if (!calendar.getProperty("VERSION").equals(Version.VERSION_2_0)) {
	    		logger.error("ICS file version not recognized");
	    	}

	    	if (calendar.getProperty("METHOD").equals(Method.REQUEST)) {
	    		calInfo.setAction("CREATE");
	    	} else if(calendar.getProperty("METHOD").equals(Method.CANCEL)) {
	    		calInfo.setAction("DELETE");
	    	} else {
	    		throw new Exception("Unknown method: " + calendar.getProperty("METHOD").getValue());
	    	}

	    	VEvent vevent = (VEvent) calendar.getComponent("VEVENT");
	    	if (vevent.getUid() == null) {
	    		throw new Exception("Could not determine event UID");
	    	} else {
	    		calInfo.setId(vevent.getUid().getValue());
	    	}

	    	if (vevent.getSummary() == null) {
	    		calInfo.setSummary("Untitled event");
	    	} else {
	    		calInfo.setSummary(vevent.getSummary().getValue());
	    	}

	    	calInfo.setCreateDate(vevent.getCreated().getDate());
	    	calInfo.setStartDate(vevent.getStartDate().getDate());
	    	calInfo.setEndDate(vevent.getEndDate().getDate());

	    	if (vevent.getDescription() != null) {
	    		calInfo.setDescription(vevent.getDescription().getValue());
	    	}

	    	if (vevent.getLocation() != null) {
	    		calInfo.setLocation(vevent.getLocation().getValue());
	    	}

    	} finally {
    		if (contentStream != null) {
    			try {
    				contentStream.close();
    			} catch (Exception e) {
    			}
    		}
    	}
    	return calInfo;
	}

	/**
	 * For a given invitation, determine its "processed" folder.
	 *
	 * @param emailNodeRef Node reference of the emailed object.
	 * @return NodeRef representing the folder for the email and its attachments
	 */
	public NodeRef getProcessedFolder(NodeRef emailNodeRef) {
		ChildAssociationRef childAssoc = nodeService.getPrimaryParent(emailNodeRef); // inboundInvitations
		NodeRef parentFolder = childAssoc.getParentRef();

		// Everything goes in a common folder named PROCESSED_FOLDER_NAME
		NodeRef mainProcessedFolder = nodeService.getChildByName(parentFolder, ContentModel.ASSOC_CONTAINS, PROCESSED_FOLDER_NAME);
		if (mainProcessedFolder == null) {
			FileInfo fileInfo = fileFolderService.create(parentFolder, PROCESSED_FOLDER_NAME, ContentModel.TYPE_FOLDER);
			mainProcessedFolder = fileInfo.getNodeRef();
		}

		// Within that, create a folder using the email's nodeRef ID to avoid
		// naming collisions and to keep the email and attachments together
		FileInfo fileInfo = fileFolderService.create(mainProcessedFolder, emailNodeRef.getId(), ContentModel.TYPE_FOLDER);
		NodeRef processedFolder = fileInfo.getNodeRef();

		return processedFolder;
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

	public SiteService getSiteService() {
		return siteService;
	}

	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public SearchService getSearchService() {
		return searchService;
	}

	public void setSearchService(SearchService searchService) {
		this.searchService = searchService;
	}

	public FileFolderService getFileFolderService() {
		return fileFolderService;
	}

	public void setFileFolderService(FileFolderService fileFolderService) {
		this.fileFolderService = fileFolderService;
	}

	public class CalendarInfo {
		private Date createDate;
		private String summary;
		private String description;
		private Date startDate;
		private Date endDate;
		private String location;
		private String id;
		private String action;
		public String getSummary() {
			return summary;
		}
		public void setSummary(String summary) {
			this.summary = summary;
		}
		public String getDescription() {
			return description;
		}
		public void setDescription(String description) {
			this.description = description;
		}
		public Date getStartDate() {
			return startDate;
		}
		public void setStartDate(Date startDate) {
			this.startDate = startDate;
		}
		public Date getEndDate() {
			return endDate;
		}
		public void setEndDate(Date endDate) {
			this.endDate = endDate;
		}
		public String getLocation() {
			return location;
		}
		public void setLocation(String location) {
			this.location = location;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
		public String getAction() {
			return action;
		}
		public void setAction(String action) {
			this.action = action;
		}
		public Date getCreateDate() {
			return createDate;
		}
		public void setCreateDate(Date createDate) {
			this.createDate = createDate;
		}
	}
}

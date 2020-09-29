inbound-invites
==============

This add-on gives you the ability to send calendar invitations to an Alfresco Share site. This provides a very basic calendar integration in which users can select which events they create in the corporate email and calendaring system will show up in the Share site calendar.

This should work with any mail system that sends ICS files (mimetype of "text/calendar") when it sends calendar invitations.

There are two behaviors that make this work.

  1. When Site nodes are updated, a behavior ensures that a folder exists to store processed invitations and assigns the folder an email alias equal to the site's short name.
  2. When emailed nodes are updated, a behavior invokes an invitation processor class. That's where the logic resides that knows how to create, update, or delete Alfresco calendar event nodes based on the ICS file attachment.

It is important to realize that this integration requires people to proactively invite the Share site to every meeting they want to show up on the Share site calendar.

Essentially, your Share site acts like any other person or resource (like a conference room) that you might want to send a meeting invitation to.

If you update or delete the event in the source calendar system as long as the Share site receives an update, the update will be reflected in the Alfresco Share site calendar.

However, the integration is one-way. If the calendar data is changed on the Alfresco Share side, nothing is communicated back to the source calendar system.

Pre-requisites
--------------
You must configure your Alfresco server for inbound SMTP for this to work. That may require working with your email administrators because you need to be able to route email from your corporate email servers to Alfresco.

Because this add-on leverages inbound SMTP, all normal requirements for sending mail to Alfresco apply. For example:

1. Senders must belong to the "EMAIL_CONTRIBUTORS" group.
2. The user object in Alfresco must have an email address that matches the sender's email address.
3. The user must have write access to the Share site they are sending the invitation to.

For more information on configuring Alfresco for inbound SMTP, consult the [docs](http://docs.alfresco.com).

Maven
-----
Add the dependencies and overlays to the POM files of your WAR projects.

For the repository tier, in a project created with the all-in-one archetype, edit repo/pom.xml:

    <dependencies>
      ...
      <dependency>
          <groupId>com.metaversant</groupId>
          <artifactId>inbound-invites-repo</artifactId>
          <version>1.1.0</version>
          <type>amp</type>
      </dependency>
      ...
    </dependencies>

    <overlays>
      ...
      <overlay>
          <groupId>com.metaversant</groupId>
          <artifactId>inbound-invites-repo</artifactId>
          <type>amp</type>
      </overlay>
      ...
    </overlays>

Manual Installation
-------------------
There is one AMP associated with this add-on. It is for the "repo tier".

From the root of the inbound-invites-repo directory, use `mvn install` to create the AMP. By default the POM is set to depend on Alfresco Community Edition 5.0.d, depending on which branch of this code you checkout. This has not been tested with Alfresco Enterprise Edition.

### Install the AMPs

You can install the AMP as you normally would using the MMT. For example, to install on a server, you would copy `inbound-invites-repo.amp` to `$ALFRESCO_HOME/amps`, then run `bin/apply_amps.sh`.

For developers looking to contribute who are running locally, you can use the Maven plug-in to install the AMP by running `mvn alfresco:install -Dmaven.alfresco.warLocation=$TOMCAT_HOME/webapps/alfresco`. If you are not running your Alfresco WAR expanded, specify the WAR file path instead of the directory.

Once the AMP is deployed, start up Alfresco.

### Usage

Once the AMP is installed, any time you create a new Share site it will be given a folder used to hold processed invitations and the folder will be assigned an email alias equal to the Share site's short name. For example, if the Share site's URL is "test-site-1" then an email alias named "test-site-1" will be assigned.

Assuming your inbound SMTP configuration is set up correctly, you should now be able to send calendar invitations to test-site-1@alfresco.yourdomain.com and see those invitations show up in the test-site-1 calendar.

Contributing
------------
Please file issues for this project on its Github page. I gladly accept pull requests.

Technical Details
-----------------
There are some sample ICS files in src/main/resources. These were created using Mozilla Thunderbird.

Calendar objects in Alfresco are instances of "ia:calendarEvent". The project uses ical4j to read ICS files. It sets metadata as follows:

    ical = Alfresco property
    Summary = ia:whatEvent
    Description = ia:descriptionEvent
    startDate = ia:fromDate
    endDate = ia:toDate
    location = ia:whereEvent
    uid = ia:outlookUID

Calendar objects reside in a folder of type ia:calendar, which is always named cm:calendar. So, for a Share site named "test-site-1", calendar objects reside in:

    /app:company_home/st:sites/cm:test-site-1/cm:calendar

The ical ID is how the originating calendar server keeps track of events. We store that in an Alfresco property named `ia:outlookUID`. When an ICS file is processed, the invitationProcessor class does a search to see if an event with that ID already exists in the site's calendar. If it does, it updates the event. Otherwise, it creates a new event and sets the property to the ID.

### Testing

The local test alfresco-global.properties file has the inbound SMTP server enabled. This makes it possible to use the Maven integration-test to launch the repository, then you can use a test email and calendaring setup to test the add-on.

Because this project is repo-only, you'll need to run a separate Tomcat for Alfresco Share. You can do that either by creating a dummy Alfresco Share project with the Maven SDK and running its integration-test or you can just install the Share WAR in your own Tomcat installation running on some port other than 8080.

Here are the steps:

1. Switch to the root of the repo project, then run `mvn integration-test -Pamp-to-war`.

2. After startup, create a test user with a test email address, then add the user to the EMAIL_CONTRIBUTORS group.

3. Now open up a local email client such as Mozilla Thunderbird, and configure the SMTP server to be localhost:1025 for the same test email address you added to the test user in Alfresco.

4. You should be able to send an email to any folder in Alfresco.

5. Now log in to Share and create a test Share site. Invite the test user as a collaborator.

6. You should be able to send a calendar invitation to the Share site using the site ID.

7. Log in to Share and you should see the event in the Share site calendar.

8. Update the event in your calendar client and the event should change in the Share site calendar.

9. Delete the event in your calendar client and the event should be removed from the Share site calendar.

## Signing built artifacts

To build and sign artifacts before publishing to Maven Central, run:

    mvn -DperformRelease=true clean install -DskipTests


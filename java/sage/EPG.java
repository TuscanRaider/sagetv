/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

import java.lang.reflect.Constructor;

import sage.Wizard.MaintenanceType;

/*
 * Usage in other classes:
 * Logo & color retrieval for EPG UI stuff, EPGDSEditMenu & GuideSetup for editing,
 * Sage for initialization
 */
public final class EPG implements Runnable
{
  public static final String EPG_DATA_SOURCES_KEY = "epg_data_sources";
  static final String EPG_KEY = "epg";
  protected static final String CATEGORY_COLORS_KEY = "category_colors";
  public static final String EPG_CLASS = "epg_class";
  static final String LOGO_DIR = "logo_dir";
  private static final String DOWNLOAD_WHILE_INACTIVE = "download_while_inactive";
  private static final String DOWNLOAD_FREQUENCY = "download_frequency";
  private static final String DOWNLOAD_OFFSET = "download_offset";
  private static final String CHANNEL_LINEUPS = "channel_lineups";
  private static final String PHYSICAL_CHANNEL_LINEUPS = "physical_channel_lineups";
  private static final String LINEUP_OVERRIDES = "lineup_overrides";
  private static final String LINEUP_PHYSICAL_OVERRIDES = "lineup_physical_overrides";
  private static final String SERVICE_LEVELS = "service_levels";
  private static final String ACCESS_CODE = "access_code";
  private static final String ZIP_CODE = "zip_code";
  private static final String EPG_IMPORT_PLUGIN = "epg_import_plugin";
  private static final String AUTODIAL = "autodial";
  private static final long ERROR_SLEEP = 1800000L;
  static final long MAINTENANCE_FREQ = Sage.MILLIS_PER_DAY;

  /**
   * EPG state Enum
   */
  private enum EpgState {
    IDLE("Idle"),
    MAINTENANCE("Maintenance"),
    UPDATING("Updating");

    private final String reportingText;
    private EpgState(String reportingText){
      this.reportingText=reportingText;
    }
    String getReportingText() {
      return reportingText;
    }
  }

  private EpgState epgState = EpgState.IDLE;

  public static EPG prime()
  {
    return getInstance();
  }

  private static class EPGHolder
  {
    public static final EPG instance = new EPG();
  }

  public static EPG getInstance()
  {
    return EPGHolder.instance;
  }

  private EPG()
  {
    wiz = Wizard.getInstance();
    mmc = MMC.getInstance();

    dsPrefs = EPG_DATA_SOURCES_KEY + '/';
    prefs = EPG_KEY + '/';

    accessCode = Sage.get(prefs + ACCESS_CODE, SageTV.oneWayEncrypt("TRIAL"));
    zipCode = Sage.get(prefs + ZIP_CODE, "");
    autodial = Sage.getBoolean(prefs + AUTODIAL, false);

    buildSourcesFromPrefs();

    lineups = new java.util.HashMap();
    serviceLevels = new java.util.HashMap();
    lineupOverrides = new java.util.HashMap();
    providerNameToIDCache = new java.util.HashMap();
    physicalLineups = new java.util.HashMap();
    physicalLineupOverrides = new java.util.HashMap();

    buildLineupsFromPrefs();
    String[] slKeys = Sage.keys(prefs + SERVICE_LEVELS + '/');
    for (int i = 0; i < slKeys.length; i++)
    {
      java.util.Map currMap = parseChanMapString(Sage.get(prefs + SERVICE_LEVELS + '/' + slKeys[i], ""));
      if (!currMap.isEmpty())
      {
        try
        {
          long testLong = Long.parseLong(slKeys[i]);
        }
        catch (NumberFormatException e)
        {
          slKeys[i] = Long.toString(Sage.convertProviderID(slKeys[i]));
        }
        serviceLevels.put(new Long(slKeys[i]), currMap);
      }
    }
    if (SageTV.upgradeFromLite)
    {
      logoDir = new java.io.File(Sage.getPath("data", "ChannelLogos"));
      if (!Sage.EMBEDDED)
        Sage.put(prefs + LOGO_DIR, logoDir.toString());
    }
    else
    {
      String logoDirStr = Sage.get(prefs + LOGO_DIR, null);
      if (logoDirStr == null)
      {
        logoDir = new java.io.File(Sage.getPath("data", "ChannelLogos"));
        if (!Sage.EMBEDDED)
          Sage.put(prefs + LOGO_DIR, logoDir.toString());
      }
      else
      {
        logoDir = new java.io.File(logoDirStr);
      }
    }
    logoDir.mkdirs();
    logoMap = new java.util.HashMap();

    /*colorMap = new java.util.HashMap();
		String catColorPrefs = prefs + CATEGORY_COLORS_KEY + '/';
		boolean initColors = (Sage.keys(catColorPrefs).length == 0);
//		if (initColors)
		{
			Sage.put(catColorPrefs + "adult", "185,70,70");
			Sage.put(catColorPrefs + "news", "220,0,220");
			Sage.put(catColorPrefs + "movie", "194,194,58");
			Sage.put(catColorPrefs + "sports event", "0,0,225");
			Sage.put(catColorPrefs + "sports non-event", "0,0,225");
			Sage.put(catColorPrefs + "sports talk", "0,0,225");
			Sage.put(catColorPrefs + "sports", "0,0,225");
		}
		String[] catNames = Sage.keys(catColorPrefs);
		java.util.regex.Pattern colorPat = java.util.regex.Pattern.compile(
			"(\\d+)\\,(\\d+)\\,(\\d+)");
		for (int i = 0; i < catNames.length; i++)
		{
			String catData = Sage.get(catColorPrefs + catNames[i], "");
			java.util.regex.Matcher match = colorPat.matcher(catData);
			if (!match.matches())
			{
				System.out.println("Invalid category color for " + catNames[i] +
					" of " + catData);
				continue;
			}
			int r = Integer.parseInt(match.group(1));
			int g = Integer.parseInt(match.group(2));
			int b = Integer.parseInt(match.group(3));
			r = Math.min(Math.max(r, 0), 255);
			g = Math.min(Math.max(g, 0), 255);
			b = Math.min(Math.max(b, 0), 255);
			java.awt.Color catColor = new java.awt.Color(r, g, b, 128);
			colorMap.put(catNames[i].toLowerCase(), catColor);
		}*/

    downloadWhileInactive = Sage.getBoolean(prefs + DOWNLOAD_WHILE_INACTIVE, false);
    downloadFrequency = 0;//Sage.getInt(prefs + DOWNLOAD_FREQUENCY, 0);
    downloadOffset = Sage.getInt(prefs + DOWNLOAD_OFFSET, 0);

    inactive = false;

    if (downloadFrequency > 0)
    {
      java.util.Calendar cal = new java.util.GregorianCalendar();
      cal.set(java.util.Calendar.MINUTE, 0);
      cal.set(java.util.Calendar.SECOND, 0);
      cal.set(java.util.Calendar.MILLISECOND, 0);
      cal.set(java.util.Calendar.HOUR_OF_DAY, downloadOffset);
      nextDownloadTime = cal.getTimeInMillis();
      if (Sage.DBG) System.out.println("EPG first download time for today is " + Sage.df(nextDownloadTime));
      while (nextDownloadTime < Sage.time())
      {
        nextDownloadTime += downloadFrequency * Airing.MILLIS_PER_HOUR;
      }
      if (Sage.DBG) System.out.println("EPG next download time is " + Sage.df(nextDownloadTime));
    }
    else nextDownloadTime = 0;

    updateEPGPluginObj();
  }

  private void updateEPGPluginObj()
  {
    String epgImportPluginName = Sage.get(prefs + EPG_IMPORT_PLUGIN, "").replaceAll(" ", ""); // bugfix for nielm because this is a common config error;
    if (epgImportPluginName.length() > 0 && (epgImportPlugin == null || !epgImportPluginName.equals(epgPluginString)))
    {
      epgImportPlugin = null;
      try
      {
        epgImportPlugin = (EPGImportPlugin) Class.forName(epgImportPluginName, true, Sage.extClassLoader).newInstance();
      }
      catch (Throwable e)
      {
        if (Sage.DBG) System.out.println("Error loading EPG Import Plugin of:" + e);
        if (Sage.DBG) System.out.println("DISABLING EPG IMPORT PLUGIN SINCE IT FAILS LOADING!!!");
        Sage.put(prefs + EPG_IMPORT_PLUGIN, epgImportPluginName= "");
      }
      epgPluginString = epgImportPluginName;
    }
    else
    {
      epgPluginString = epgImportPluginName;
      if (epgPluginString.length() == 0)
        epgImportPlugin = null;
    }
  }

  private void buildSourcesFromPrefs()
  {
    String[] dsNames = Sage.childrenNames(dsPrefs);
    java.util.Arrays.sort(dsNames);
    sources = new java.util.Vector();
    String customEpgSourceClass = Sage.get("epg/custom_data_source_class", "");
    for (int i = 0; i < dsNames.length; i++)
    {
      String currPrefs = dsPrefs + dsNames[i] + '/';
      String currClassStr = Sage.get(currPrefs + EPG_CLASS, null);
      EPGDataSource newEPGDS = null;
      try
      {
        if (!Sage.EMBEDDED && currClassStr != null && currClassStr.equals("Basic"))
        {
          sources.addElement(newEPGDS = new EPGDataSource(Integer.parseInt(dsNames[i])));
        }
        else if ((currClassStr != null && currClassStr.indexOf('.') != -1) ||
            customEpgSourceClass.length() > 0)
        {
          if (currClassStr == null || currClassStr.indexOf('.') == -1)
            currClassStr = customEpgSourceClass;
          if (Sage.DBG) System.out.println("Loading dynamic EPG data source class of: " + currClassStr);
          Class epgClass = Class.forName(currClassStr);
          Constructor cstr = epgClass.getDeclaredConstructor(Integer.TYPE);
          newEPGDS = (EPGDataSource) cstr.newInstance(Integer.parseInt(dsNames[i]));
          sources.addElement(newEPGDS);
        }
				else
				{
          sources.addElement(newEPGDS = new WarlockRipper(Integer.parseInt(dsNames[i])));
        }
      }
      catch (Throwable e)
      {
        System.out.println("ERROR: Exception creating " +
            currClassStr + " for pref " + currPrefs + " of " + e + " More:");
        e.printStackTrace();
        Sage.removeNode(dsPrefs + dsNames[i]);
        continue;
      }
      // Check if there's a duplicate for this provider ID, and if so, remove it
      for (int j = 0; j < sources.size(); j++)
      {
        EPGDataSource tempSource = (EPGDataSource) sources.get(j);
        if (tempSource == newEPGDS)
          continue;
        if (tempSource.getProviderID() == newEPGDS.getProviderID())
        {
          System.out.println("Removing redundant EPG data source for provider ID:" + tempSource.getProviderID());
          sources.remove(j);
          Sage.removeNode(dsPrefs + tempSource.getEPGSourceID());
          j--;
        }
      }
      // If this is a zero provider ID then it's a bad temp object, destroy it
      if (newEPGDS.getProviderID() == 0)
      {
        System.out.println("Removing EPGDataSource with a zero providerID.");
        sources.remove(newEPGDS);
        Sage.removeNode(dsPrefs + newEPGDS.getEPGSourceID());
      }
    }
    resetViewableStationsCache();
  }

  private void buildLineupsFromPrefs()
  {
    // SYNCHRONIZATION NOTE: You may obtain the physicalLineups lock and then acquire the lineups lock. But NEVER acquire
    // the lineups lock and then the physicalLineups lock.
    synchronized (physicalLineups)
    {
      synchronized (lineups)
      {
        lineups.clear();
        lineupOverrides.clear();
        physicalLineups.clear();
        physicalLineupOverrides.clear();
        String[] lineupKeys = Sage.keys(prefs + CHANNEL_LINEUPS + '/');
        for (int i = 0; i < lineupKeys.length; i++)
        {
          java.util.Map currMap = parseMultiChanMapString(Sage.get(prefs + CHANNEL_LINEUPS + '/' + lineupKeys[i], ""));
          if (!currMap.isEmpty())
          {
            try
            {
              long testLong = Long.parseLong(lineupKeys[i]);
            }
            catch (NumberFormatException e)
            {
              lineupKeys[i] = Long.toString(Sage.convertProviderID(lineupKeys[i]));
            }
            lineups.put(new Long(lineupKeys[i]), currMap);
          }
        }
        lineupKeys = Sage.keys(prefs + LINEUP_OVERRIDES + '/');
        for (int i = 0; i < lineupKeys.length; i++)
        {
          java.util.Map currMap = parseMultiChanMapString(Sage.get(prefs + LINEUP_OVERRIDES + '/' + lineupKeys[i], ""));
          if (!currMap.isEmpty())
          {
            try
            {
              long testLong = Long.parseLong(lineupKeys[i]);
            }
            catch (NumberFormatException e)
            {
              lineupKeys[i] = Long.toString(Sage.convertProviderID(lineupKeys[i]));
            }
            lineupOverrides.put(new Long(lineupKeys[i]), currMap);
            java.util.Map overMap = (java.util.Map)lineups.get(new Long(lineupKeys[i]));
            if (overMap == null)
              lineups.put(new Long(lineupKeys[i]), overMap = new java.util.HashMap());
            overMap.putAll(currMap);
          }
        }
        lineupKeys = Sage.keys(prefs + PHYSICAL_CHANNEL_LINEUPS + '/');
        for (int i = 0; i < lineupKeys.length; i++)
        {
          java.util.Map currMap = parseMultiChanMapString(Sage.get(prefs + PHYSICAL_CHANNEL_LINEUPS + '/' + lineupKeys[i], ""));
          if (!currMap.isEmpty())
          {
            try
            {
              long testLong = Long.parseLong(lineupKeys[i]);
            }
            catch (NumberFormatException e)
            {
              lineupKeys[i] = Long.toString(Sage.convertProviderID(lineupKeys[i]));
            }
            physicalLineups.put(new Long(lineupKeys[i]), currMap);
          }
        }
        lineupKeys = Sage.keys(prefs + LINEUP_PHYSICAL_OVERRIDES + '/');
        for (int i = 0; i < lineupKeys.length; i++)
        {
          java.util.Map currMap = parseMultiChanMapString(Sage.get(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + lineupKeys[i], ""));
          if (!currMap.isEmpty())
          {
            try
            {
              long testLong = Long.parseLong(lineupKeys[i]);
            }
            catch (NumberFormatException e)
            {
              lineupKeys[i] = Long.toString(Sage.convertProviderID(lineupKeys[i]));
            }
            physicalLineupOverrides.put(new Long(lineupKeys[i]), currMap);
            java.util.Map overMap = (java.util.Map)physicalLineups.get(new Long(lineupKeys[i]));
            if (overMap == null)
              physicalLineups.put(new Long(lineupKeys[i]), overMap = new java.util.HashMap());
            overMap.putAll(currMap);
          }
        }
      }
    }
    resetViewableStationsCache();
  }

  void resyncToProperties(boolean rebuildDS, boolean rebuildLineups)
  {
    alive = true;
    if (rebuildDS)
      buildSourcesFromPrefs();
    if (rebuildLineups)
      buildLineupsFromPrefs();
  }

  void resetViewableStationsCache()
  {
    synchronized (stationIDCacheLock)
    {
      viewableStationIDCache = null;
      VideoFrame.clearSortedChannelLists();
    }
    // NOTE: We didn't need this in our old UI because we would call RemoveUnusedLineups whenever someone was exiting the
    // channel setup UIs which would then kick the scheduler. Now that it's happening via backend processes we need to kick it
    // every time we change the enabled channels so it redoes the schedule and then also kicks the Seeker for what channels are viewable.
    if (!Sage.client)
    {
      Scheduler.getInstance().kick(true);
    }
  }

  /*	public java.awt.Color getCategoryColor(String name)
	{
		if (name == null) return null;
		return (java.awt.Color) colorMap.get(name.toLowerCase());
	}*/

  public String getZipCode() { return zipCode; }
  public void setZipCode(String x)
  {
    Sage.put(prefs + ZIP_CODE, zipCode = x);
  }

  // Returns the MD5 encrypted access code
  public String getAccessCode() { return accessCode; }

  void setAccessCode(String x)
  {
    if (x == null)
    {
      Sage.put(prefs + ACCESS_CODE, null);
      return;
    }
    else if (x.length() == 0)
    {
      Sage.put(prefs + ACCESS_CODE, "");
      return;
    }
    String newCode = SageTV.oneWayEncrypt(x);
    if (!newCode.equals(accessCode))
    {
      Sage.put(prefs + ACCESS_CODE, accessCode = newCode);

      // Our login criteria has changed so we need to clear out the
      // update IDs
      for (int i = 0; i < sources.size(); i++)
        ((EPGDataSource) sources.elementAt(i)).reset();
    }
  }

  private java.io.File getLogoPathInternal(String name)
  {
    if (logoMap.containsKey(name))
      return (java.io.File) logoMap.get(name);
    java.io.File logoFile = new java.io.File(logoDir, name + ".gif");
    if (logoFile.isFile())
    {
      logoMap.put(name, logoFile);
      return logoFile;
    }
    if ((logoFile = new java.io.File(logoDir, name + ".jpg")).isFile())
    {
      logoMap.put(name, logoFile);
      return logoFile;
    }
    if ((logoFile = new java.io.File(logoDir, name + ".png")).isFile())
    {
      logoMap.put(name, logoFile);
      return logoFile;
    }
    if (!Sage.WINDOWS_OS)
    {
      String srclc = name.toLowerCase();
      if ((logoFile = new java.io.File(logoDir, srclc + ".gif")).isFile())
      {
        logoMap.put(name, logoFile);
        return logoFile;
      }
      if ((logoFile = new java.io.File(logoDir, srclc + ".jpg")).isFile())
      {
        logoMap.put(name, logoFile);
        return logoFile;
      }
      if ((logoFile = new java.io.File(logoDir, srclc + ".png")).isFile())
      {
        logoMap.put(name, logoFile);
        return logoFile;
      }
      // Try the cleaned file name
      String cleanName = MediaFile.createValidFilename(name);
      if (!cleanName.equals(name))
      {
        logoFile = getLogoPathInternal(cleanName);
        logoMap.put(name, logoFile);
        return logoFile;
      }
    }
    logoMap.put(name, null);
    return null;
  }

  public Object getLogoPath(Channel c)
  {
    java.io.File logoPath = getLogoPathInternal(c.getName());
    if (logoPath == null)
    {
      // Check for a Zap2it logo
      if (c.getLogoCount(Channel.LOGO_MED) > 0)
      {
        return c.getLogoUrl(0, Channel.LOGO_MED);
      }
      return null;
    }
    else
      return logoPath;
  }

  public MetaImage getLogo(String name, ResourceLoadListener loader)
  {
    java.io.File logoPath = getLogoPathInternal(name);
    if (logoPath == null)
      return null;
    else if (loader != null && loader.getUIMgr() != null && loader.getUIMgr().getBGLoader() != null)
      return loader.getUIMgr().getBGLoader().getMetaImageFast(logoPath, loader, null);
    else
      return MetaImage.getMetaImage(logoPath);
  }

  public MetaImage getLogo(Channel c, ResourceLoadListener loader)
  {
    if (c == null) return null;
    java.io.File logoPath = getLogoPathInternal(c.getName());
    if (logoPath == null)
    {
      // Check for a Zap2it logo
      if (c.getLogoCount(Channel.LOGO_MED) > 0)
      {
        if (loader != null && loader.getUIMgr() != null && loader.getUIMgr().getBGLoader() != null)
          return loader.getUIMgr().getBGLoader().getMetaImageFast(c.getLogoUrl(0, Channel.LOGO_MED), loader, null);
        else
          return MetaImage.getMetaImage(c.getLogoUrl(0, Channel.LOGO_MED));
      }
      return null;
    }
    else if (loader != null && loader.getUIMgr() != null && loader.getUIMgr().getBGLoader() != null)
      return loader.getUIMgr().getBGLoader().getMetaImageFast(logoPath, loader, null);
    else
      return MetaImage.getMetaImage(logoPath);
  }

  public void addLogo(byte[] logoData, String name)
  {
    synchronized (logoMap)
    {
      java.io.File logoFile = new java.io.File(logoDir, name +
          ((logoData[0] == 'G') ? ".gif" : ".jpg"));
      java.io.OutputStream os = null;
      try
      {
        os = new java.io.FileOutputStream(logoFile);
        os.write(logoData);
      }
      catch (java.io.IOException e)
      {
        System.out.println("Error writing logo file of:" + e);
      }
      finally
      {
        if (os != null) try{os.close();}catch(java.io.IOException e){}
      }
    }
  }

  public void resetLogoMap()
  {
    synchronized (logoMap)
    {
      logoMap.clear();
    }
  }

  public java.io.File getLogoDir()
  {
    return logoDir;
  }

  public void run()
  {
    // Check if the DB file was deleted
    if (wiz.getChannels().length < 4)
    {
      for (int i = 0; i < sources.size(); i++){
        try {
          ((EPGDataSource) sources.elementAt(i)).reset();
        } catch (Throwable e) {
          System.out.println("EPG EXCEPTION OCCURRED: " + e);
          e.printStackTrace();
        }
      }
    }

    for (int i = 0; i < sources.size(); i++)
    {
      try {
        ((EPGDataSource) sources.elementAt(i)).initDataScanInfo();
      } catch (Throwable e) {
        System.out.println("EPG EXCEPTION OCCURRED: " + e);
        e.printStackTrace();
      }
    }
    Wizard.MaintenanceType reqMaintenanceType=MaintenanceType.NONE;
    // Check for the default channel setup on the different sources
    boolean[] didAdd = new boolean[1];
    boolean updateFinished = true;
    while (alive)
    {
      try{

        if (Sage.time() - wiz.getLastMaintenance() > MAINTENANCE_FREQ)
          reqMaintenanceType = MaintenanceType.FULL;

        if (reqMaintenanceType != MaintenanceType.NONE)
        {
          Carny.getInstance().kickHard();
          Scheduler.getInstance().kick(false);
        }

        if (reqMaintenanceType != MaintenanceType.NONE
            && (!downloadWhileInactive || inactive))
        {
          try {
            epgState=EpgState.MAINTENANCE;
            wiz.maintenance(reqMaintenanceType);
          } finally {
            epgState=EpgState.IDLE;
          }
          reqMaintenanceType = MaintenanceType.NONE;
        }

        long minWait = MAINTENANCE_FREQ - (Sage.time() - wiz.getLastMaintenance());
        if (minWait <= 0) minWait = 1;
        synchronized (sources)
        {
          for (int i = 0; (i < sources.size()) && alive; i++)
          {
            long currWait = ((EPGDataSource) sources.elementAt(i)).getTimeTillUpdate();
            minWait = Math.min(minWait, currWait);
            if (Sage.DBG) System.out.println(sources.elementAt(i) + " needs an update in " + Sage.durFormat(currWait));
          }
        }
        if (Sage.DBG) System.out.println("EPG needs an update in " + (minWait/60000) + " minutes");
        if (minWait > 0)
        {
          if (!updateFinished && (downloadFrequency != 0))
          {
            nextDownloadTime += downloadFrequency;
            if (Sage.DBG) System.out.println("EPG nextDownloadTime=" + Sage.df(nextDownloadTime));
          }
          updateFinished = true;
          Sage.disconnectInternet();
          if (Sage.DBG) System.out.println("EPG's works is done. Waiting...");
          synchronized (sources)
          {
            if (alive)
              try{sources.wait(minWait + 15000L);} catch(InterruptedException e){}
          }
        }
        else if (downloadWhileInactive && !inactive)
        {
          if (Sage.DBG) System.out.println("EPG is waiting because of system activity...");
          Sage.disconnectInternet();
          synchronized (sources)
          {
            if (alive)
              try{sources.wait(minWait);} catch(InterruptedException e){}
          }
        }
        else if (nextDownloadTime > Sage.time())
        {
          long nextWait = Math.min(minWait, nextDownloadTime - Sage.time());
          if (nextWait > 0)
          {
            if (Sage.DBG) System.out.println("EPG is waiting for next scheduled download time in " + nextWait/60000L + " minutes");
            synchronized (sources)
            {
              if (alive)
                try{sources.wait(nextWait + 15000L);} catch(InterruptedException e){}
            }
          }
        }
        else
        {
          updateFinished = false;
          boolean updatesFailed = false;
          // Connect when we become active
          if (!autodial || Sage.connectToInternet())
          {
            java.util.ArrayList highPriorityDownloads = new java.util.ArrayList();
            synchronized (sources)
            {
              for (int i = 0; i < sources.size(); i++)
              {
                if (!((EPGDataSource) sources.get(i)).isChanDownloadComplete())
                  highPriorityDownloads.add(sources.get(i));
              }
            }
            for (int i = 0; (i < highPriorityDownloads.size()) && alive; i++)
            {
              currDS = (EPGDataSource) highPriorityDownloads.get(i);
              synchronized (sources)
              {
                if (!sources.contains(currDS))
                  continue;
              }
              currDS.clearAbort();
              if (Sage.DBG) System.out.println("EPG PRIORITY EXPANSION attempting to expand " + currDS.getName());
              boolean updateSucceeded=false;
              try{
                epgState=EpgState.UPDATING;
                updateSucceeded=currDS.expand();
              } finally {
                epgState=EpgState.IDLE;
              }
              if (! updateSucceeded)
              {
                updatesFailed = handleEpgDsUpdateFailed(updatesFailed);
              }
              else
              {
                // set the next maintenance type based on how mucgh
                // this EPG update thinks should be done...
                reqMaintenanceType = checkEpgDsMaintenanceType(reqMaintenanceType);
                epgErrorSleepTime = 60000;
              }
              synchronized (sources)
              {
                currDS.clearAbort();
                currDS = null;
              }
            }
            for (int i = 0; (i < sources.size()) && alive; i++)
            {
              synchronized (sources)
              {
                if ((i < sources.size()) && alive)
                {
                  currDS = (EPGDataSource) sources.elementAt(i);
                }
                else
                {
                  break;
                }
              }
              if (highPriorityDownloads.contains(currDS))
                continue;
              currDS.clearAbort();
              if (Sage.DBG) System.out.println("EPG attempting to expand " + currDS.getName());
              boolean updateSucceeded=false;
              try{
                epgState=EpgState.UPDATING;
                updateSucceeded=currDS.expand();
              } finally {
                epgState=EpgState.IDLE;
              }
              if (! updateSucceeded)
              {
                updatesFailed = handleEpgDsUpdateFailed(updatesFailed);
              }
              else
              {
                reqMaintenanceType = checkEpgDsMaintenanceType(reqMaintenanceType);
                epgErrorSleepTime = 60000;
              }
              synchronized (sources)
              {
                currDS.clearAbort();
                currDS = null;
              }
            }

            // NOTE: If the user had 2 sources, and one was failing on the update, we don't want to
            // continually save the DB each round until the update is complete. That'd be bad.
            if (updatesFailed)
              reqMaintenanceType = MaintenanceType.NONE;
            else
              sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.EPG_UPDATE_COMPLETED, (Object[]) null);
          }
          else
          {
            if (Sage.DBG) System.out.println("ERROR Could not autodial...waiting...");
            synchronized (sources)
            {
              if (alive)
                try{sources.wait(ERROR_SLEEP);} catch(InterruptedException e){}
            }
          }
        }

        // Don't try to save the prefs if we're going down to avoid corrupting it since the main shutdown code will handle this
        if (alive)
          Sage.savePrefs();
      }
      catch (Throwable terr)
      {
        System.out.println("EPG EXCEPTION OCCURRED: " + terr);
        terr.printStackTrace();
      }
    }
  }
  /**
   * Handle logging errors and epg backoff timer
   *
   * @param updatesFailed - current value of updatesFailed
   * @return new value for updatesFailed (depending on whether this DS was aborted)
   */
  private boolean handleEpgDsUpdateFailed(boolean updatesFailed) {
    if (Sage.DBG) System.out.println("ERROR Updating EPG Source " + currDS.getName());
    if (Sage.DBG) System.out.println("ErrorText:" + currDS.getNewErrorText());
    if (!currDS.abort)
    {
      updatesFailed = true;
      // Sleep for a little bit when this occurs
      synchronized (sources)
      {
        if (alive)
          try{sources.wait(epgErrorSleepTime);} catch(InterruptedException e){}
      }
      epgErrorSleepTime *= 2;
      // do not exponentially back off for ever!
      epgErrorSleepTime=Math.min(Sage.MILLIS_PER_HR,epgErrorSleepTime);
    }
    return updatesFailed;
  }
  /**
   * Update required maintenance type based on what this DS is suggesting
   *
   * @param reqMaintenanceType current required maintenance type
   * @return new value for reqMaintenanceType
   */
  private Wizard.MaintenanceType checkEpgDsMaintenanceType(
      Wizard.MaintenanceType reqMaintenanceType) {
    MaintenanceType newMaintenanceType = currDS.getRequiredMaintenanceType();
    switch ( reqMaintenanceType) {
      case NONE:
        reqMaintenanceType=newMaintenanceType;
        break;
      case PATCH_EPG_HOLES:
        if ( newMaintenanceType != MaintenanceType.FULL) {
          reqMaintenanceType=newMaintenanceType;
        }
        break;
      case FULL:
        // already at full - cannot get any worse...
        break;
    }
    return reqMaintenanceType;
  }

  void goodbye()
  {
    alive = false;
    synchronized (sources)
    {
      if (currDS != null) currDS.abortUpdate();
      sources.notifyAll();
    }
  }

  public void kick()
  {
    synchronized (sources)
    {
      sources.notifyAll();
    }
  }

  void goingActive()
  {
    if (Sage.DBG) System.out.println("System is going ACTIVE, EPG is aware");
    if (inactive)
    {
      inactive = false;
      if (downloadWhileInactive)
      {
        synchronized (sources)
        {
          if (currDS != null) currDS.abortUpdate();
          sources.notifyAll();
        }
      }
    }
  }

  void goingInactive()
  {
    if (Sage.DBG) System.out.println("System is going INACTIVE, EPG is aware");
    if (!inactive)
    {
      inactive = true;
      if (downloadWhileInactive)
      {
        synchronized (sources)
        {
          sources.notifyAll();
        }
      }
    }
  }

  public long getLastRun()
  {
    long rv = 0;
    for (int i = 0; i < sources.size(); i++)
    {
      rv = Math.max(rv, ((EPGDataSource) sources.elementAt(i)).getLastRun());
    }
    return rv;
  }

  public EPGDataSource[] getDataSources()
  {
    return (EPGDataSource[]) sources.toArray(new EPGDataSource[0]);
  }

  public void addDataSource(EPGDataSource addMe)
  {
    synchronized (sources)
    {
      sources.addElement(addMe);
      sources.notifyAll();
      NetworkClient.distributeRecursivePropertyChange(dsPrefs);

      // Abort all of the current downloads so we can pickup the new lineup quickly
      for (int i = 0; i < sources.size(); i++)
        ((EPGDataSource) sources.get(i)).abortUpdate();
    }
  }

  public boolean removeDataSource(EPGDataSource killMe)
  {
    synchronized (sources)
    {
      Sage.removeNode(dsPrefs + Integer.toString(killMe.getEPGSourceID()));
      if (sources.remove(killMe))
      {
        killMe.destroySelf();
        if (killMe == currDS) currDS.abortUpdate();
        sources.notifyAll();
        NetworkClient.distributeRecursivePropertyChange(dsPrefs);
        return true;
      }
      else return false;
    }
  }

  public EPGDataSource getSourceForID(int id)
  {
    for (int i = 0; i < sources.size(); i++)
    {
      if (((EPGDataSource) sources.elementAt(i)).getEPGSourceID() == id)
      {
        return (EPGDataSource) sources.elementAt(i);
      }
    }
    return null;
  }

  public EPGDataSource getSourceForProviderID(long providerID)
  {
    if (providerID == 0) return null;
    for (int i = 0; i < sources.size(); i++)
    {
      if (providerID == ((EPGDataSource) sources.elementAt(i)).getProviderID())
      {
        return (EPGDataSource) sources.elementAt(i);
      }
    }
    return null;
  }

  public long getProviderIDForEPGDSName(String epgDSName)
  {
    if (epgDSName == null || epgDSName.length() == 0) return 0;
    for (int i = 0; i < sources.size(); i++)
    {
      if (epgDSName.equals(((EPGDataSource) sources.elementAt(i)).getName()))
      {
        return ((EPGDataSource) sources.elementAt(i)).getProviderID();
      }
    }
    return 0;
  }

  public EPGDataSource getEPGDSForEPGDSName(String epgDSName)
  {
    if (epgDSName == null || epgDSName.length() == 0) return null;
    for (int i = 0; i < sources.size(); i++)
    {
      if (epgDSName.equals(((EPGDataSource) sources.elementAt(i)).getName()))
      {
        return (EPGDataSource) sources.elementAt(i);
      }
    }
    return null;
  }

  boolean getDownloadWhileInactive() { return downloadWhileInactive; }
  void setDownloadWhileInactive(boolean x)
  {
    Sage.putBoolean(prefs + DOWNLOAD_WHILE_INACTIVE, (downloadWhileInactive = x));
  }

  int getDownloadFrequency() { return downloadFrequency; }
  void setDownloadFrequency(int x)
  {
    Sage.putInt(prefs + DOWNLOAD_FREQUENCY, (downloadFrequency = x));
  }

  int getDownloadOffset() { return downloadOffset; }
  void setDownloadOffset(int x)
  {
    Sage.putInt(prefs + DOWNLOAD_OFFSET, (downloadOffset = x));
  }

  public String guessPhysicalChanFromLogicalChan(long providerID, String channelNum)
  {
    int stationID = guessStationID(providerID, channelNum);
    if (stationID != 0)
      return getPhysicalChannel(providerID, stationID);
    return channelNum;
  }

  public int guessStationID(long providerID, String channelNum)
  {
    if (providerID != 0)
    {
      synchronized (lineups)
      {
        java.util.Map chanMap = (java.util.Map) lineups.get(new Long(providerID));
        if (chanMap == null) return 0;
        java.util.Iterator walker = chanMap.entrySet().iterator();
        while (walker.hasNext())
        {
          java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
          String[] currNums = (String[]) ent.getValue();
          for (int i = 0; i < currNums.length; i++)
            if (currNums[i].equals(channelNum))
              return ((Integer) ent.getKey()).intValue();
        }
      }
    }
    else
    {
      synchronized (lineups)
      {
        java.util.Iterator walker0 = lineups.values().iterator();
        while (walker0.hasNext())
        {
          java.util.Map chanMap = (java.util.Map) walker0.next();
          java.util.Iterator walker = chanMap.entrySet().iterator();
          while (walker.hasNext())
          {
            java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
            String[] currNums = (String[]) ent.getValue();
            for (int i = 0; i < currNums.length; i++)
              if (currNums[i].equals(channelNum))
                return ((Integer) ent.getKey()).intValue();
          }
        }
      }
    }
    return 0;
  }

  public int guessStationIDFromPhysicalChannel(long providerID, String channelNum, boolean acceptAsPrefix)
  {
    if (providerID != 0)
    {
      synchronized (physicalLineups)
      {
        synchronized (lineups)
        {
          java.util.Map chanMap = (java.util.Map) physicalLineups.get(new Long(providerID));
          java.util.Map logicalMap = (java.util.Map) lineups.get(new Long(providerID));
          if (chanMap != null && logicalMap != null)
          {
            java.util.Iterator walker = chanMap.entrySet().iterator();
            while (walker.hasNext())
            {
              java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
              // Make sure this station is actually in the logical lineup as well
              if (!logicalMap.containsKey(ent.getKey()))
                continue;
              String[] currNums = (String[]) ent.getValue();
              for (int i = 0; i < currNums.length; i++)
                if ((!acceptAsPrefix && currNums[i].equals(channelNum)) ||
                    (acceptAsPrefix && currNums[i].startsWith(channelNum)))
                  return ((Integer) ent.getKey()).intValue();
            }
          }
        }
      }
    }
    else
    {
      synchronized (physicalLineups)
      {
        java.util.Iterator walker0 = physicalLineups.values().iterator();
        while (walker0.hasNext())
        {
          java.util.Map chanMap = (java.util.Map) walker0.next();
          java.util.Iterator walker = chanMap.entrySet().iterator();
          while (walker.hasNext())
          {
            java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
            String[] currNums = (String[]) ent.getValue();
            for (int i = 0; i < currNums.length; i++)
              if ((!acceptAsPrefix && currNums[i].equals(channelNum)) ||
                  (acceptAsPrefix && currNums[i].startsWith(channelNum)))
                return ((Integer) ent.getKey()).intValue();
          }
        }
      }
    }
    return guessStationID(providerID, channelNum);
  }

  // 601 java.util.Map getLineup(long providerID)
  public java.util.Map getLineup(long providerID)
  {
    return (java.util.Map) lineups.get(new Long(providerID));
  }

  public void setLineup(long providerID, java.util.Map newMap)
  {
    lineups.put(new Long(providerID), newMap);
    Sage.put(prefs + CHANNEL_LINEUPS + '/' + providerID, createMultiChanMapString(newMap));
    java.util.Map overrideMap = (java.util.Map) lineupOverrides.get(new Long(providerID));
    if (overrideMap != null)
      newMap.putAll(overrideMap);
    NetworkClient.distributePropertyChange(prefs + CHANNEL_LINEUPS + '/' + providerID);
    resetViewableStationsCache();
  }

  public void setPhysicalLineup(long providerID, java.util.Map newMap)
  {
    physicalLineups.put(new Long(providerID), newMap);
    Sage.put(prefs + PHYSICAL_CHANNEL_LINEUPS + '/' + providerID, createMultiChanMapString(newMap));
    java.util.Map overrideMap = (java.util.Map) physicalLineupOverrides.get(new Long(providerID));
    if (overrideMap != null)
      newMap.putAll(overrideMap);
    NetworkClient.distributePropertyChange(prefs + PHYSICAL_CHANNEL_LINEUPS + '/' + providerID);
    resetViewableStationsCache();
  }

  public java.util.Map getPhysicalLineup(long providerID) {
    return (java.util.Map) physicalLineups.get(new Long(providerID));
  }

  public java.util.Map getOverrideMap(long providerID)
  {
    return (java.util.Map) lineupOverrides.get(new Long(providerID));
  }

  public boolean isOverriden(long providerID, int stationID)
  {
    java.util.Map overrideMap = (java.util.Map) lineupOverrides.get(new Long(providerID));
    if (overrideMap != null)
      return overrideMap.get(new Integer(stationID)) != null;
    return false;
  }

  public void setOverride(long providerID, int stationID, String chanNum)
  {
    setOverride(providerID, stationID, new String[] { chanNum });
  }

  public void setOverride(long providerID, int stationID, String[] chanNums)
  {
    java.util.Map overrideMap = (java.util.Map) lineupOverrides.get(new Long(providerID));
    if (overrideMap == null)
      lineupOverrides.put(new Long(providerID), overrideMap = new java.util.HashMap());
    overrideMap.put(new Integer(stationID), chanNums.clone());
    java.util.Map lineMap = (java.util.Map) lineups.get(new Long(providerID));
    if (lineMap == null)
      lineups.put(new Long(providerID), lineMap = new java.util.HashMap());
    lineMap.putAll(overrideMap);
    Sage.put(prefs + LINEUP_OVERRIDES + '/' + providerID, createMultiChanMapString(overrideMap));
    NetworkClient.distributePropertyChange(prefs + LINEUP_OVERRIDES + '/' + providerID);
    EPG.getInstance().resetViewableStationsCache();
  }

  public void clearOverride(long providerID, int stationID)
  {
    java.util.Map overrideMap = (java.util.Map) lineupOverrides.get(new Long(providerID));
    if (overrideMap == null)
      return;
    overrideMap.remove(new Integer(stationID));
    if (overrideMap.isEmpty())
      Sage.put(prefs + LINEUP_OVERRIDES + '/' + providerID, "");
    else
      Sage.put(prefs + LINEUP_OVERRIDES + '/' + providerID, createMultiChanMapString(overrideMap));
    buildLineupsFromPrefs();
    NetworkClient.distributePropertyChange(prefs + LINEUP_OVERRIDES + '/' + providerID);
  }

  public void setPhysicalOverride(long providerID, int stationID, String chanNum)
  {
    java.util.Map overrideMap = (java.util.Map) physicalLineupOverrides.get(new Long(providerID));
    if (overrideMap == null)
      physicalLineupOverrides.put(new Long(providerID), overrideMap = new java.util.HashMap());
    overrideMap.put(new Integer(stationID), new String[] { chanNum });
    java.util.Map lineMap = (java.util.Map) physicalLineups.get(new Long(providerID));
    if (lineMap == null)
      physicalLineups.put(new Long(providerID), lineMap = new java.util.HashMap());
    lineMap.putAll(overrideMap);
    Sage.put(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + providerID, createMultiChanMapString(overrideMap));
    NetworkClient.distributePropertyChange(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + providerID);
    EPG.getInstance().resetViewableStationsCache();
  }

  public void clearPhysicalOverride(long providerID, int stationID)
  {
    java.util.Map overrideMap = (java.util.Map) physicalLineupOverrides.get(new Long(providerID));
    if (overrideMap == null)
      return;
    overrideMap.remove(new Integer(stationID));
    if (overrideMap.isEmpty())
      Sage.put(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + providerID, "");
    else
      Sage.put(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + providerID, createMultiChanMapString(overrideMap));
    buildLineupsFromPrefs();
    NetworkClient.distributePropertyChange(prefs + LINEUP_PHYSICAL_OVERRIDES + '/' + providerID);
  }

  public boolean isPhysicalOverriden(long providerID, int stationID)
  {
    java.util.Map overrideMap = (java.util.Map) physicalLineupOverrides.get(new Long(providerID));
    if (overrideMap != null)
      return overrideMap.get(new Integer(stationID)) != null;
    return false;
  }

  public void setServiceLevels(long providerID, java.util.Map newMap)
  {
    serviceLevels.put(new Long(providerID), newMap);
    Sage.put(prefs + SERVICE_LEVELS + '/' + providerID, createChanMapString(newMap));
    NetworkClient.distributePropertyChange(prefs + SERVICE_LEVELS + '/' + providerID);
  }

  public java.util.Map getServiceLevels(long providerID) {
    return (java.util.Map) serviceLevels.get(new Long(providerID));
  }

  public String getChannel(long providerID, int stationID)
  {
    synchronized (lineups)
    {
      java.util.Map chanMap = (java.util.Map) lineups.get(new Long(providerID));
      if (chanMap == null) return "";
      String[] inty = (String[]) chanMap.get(new Integer(stationID));
      if (inty == null || inty.length == 0) return "";
      return inty[0];
    }
  }

  public String getPhysicalChannel(long providerID, int stationID)
  {
    synchronized (physicalLineups)
    {
      java.util.Map chanMap = (java.util.Map) physicalLineups.get(new Long(providerID));
      if (chanMap != null)
      {
        String[] inty = (String[]) chanMap.get(new Integer(stationID));
        if (inty != null && inty.length > 0)
          return inty[0];
      }
      // If we have no physical, then return the viewable logical channel
      String[] rvs = getChannels(providerID, stationID);
      EPGDataSource epgds = getSourceForProviderID(providerID);
      if (epgds == null) return "";
      for (int i = 0; i < rvs.length; i++)
      {
        if (epgds.canViewStationOnChannel(stationID, rvs[i]))
          return rvs[i];
      }
      if (rvs.length > 0)
        return rvs[0];
      return "";
    }
  }

  public String[] getChannels(long providerID, int stationID)
  {
    synchronized (lineups)
    {
      java.util.Map chanMap = (java.util.Map) lineups.get(new Long(providerID));
      if (chanMap == null) return new String[] { "" };
      String[] inty = (String[]) chanMap.get(new Integer(stationID));
      if (inty == null || inty.length == 0) return new String[] { "" };
      return inty;
    }
  }

  public int getServiceLevel(long providerID, int stationID)
  {
    java.util.Map slMap = (java.util.Map) serviceLevels.get(new Long(providerID));
    if (slMap == null) return 0;
    Integer inty = (Integer) slMap.get(new Integer(stationID));
    if (inty == null) return 0;
    return inty.intValue();
  }

  public int[] getAllStations(long providerID)
  {
    synchronized (lineups)
    {
      java.util.Map lineMap = (java.util.Map) lineups.get(new Long(providerID));
      if (lineMap == null) return new int[0];
      int[] rv = new int[lineMap.keySet().size()];
      java.util.Iterator walker = lineMap.keySet().iterator();
      int idx = 0;
      while (walker.hasNext())
        rv[idx++] = ((Integer) walker.next()).intValue();
      return rv;
    }
  }

  private static java.util.Map parseChanMapString(String str)
  {
    java.util.StringTokenizer toker = new java.util.StringTokenizer(str, ";");
    java.util.Map rv = new java.util.HashMap();
    while (toker.hasMoreTokens())
    {
      String sub = toker.nextToken();
      int idx = sub.indexOf(',');
      if (idx == -1 || idx == 0 || idx == sub.length() - 1) continue;
      try
      {
        rv.put(new Integer(sub.substring(0, idx)), new Integer(sub.substring(idx + 1)));
      }catch (NumberFormatException e){}
    }
    return rv;
  }

  private static java.util.Map parseMultiChanMapString(String str)
  {
    java.util.StringTokenizer toker = new java.util.StringTokenizer(str, ";");
    java.util.Map rv = new java.util.HashMap();
    Wizard wiz = Wizard.getInstance();
    while (toker.hasMoreTokens())
    {
      String sub = toker.nextToken();
      int idx = sub.indexOf(',');
      if (idx == -1 || idx == 0 || idx == sub.length() - 1) continue;
      try
      {
        Integer statID = new Integer(sub.substring(0, idx));
        if (!Sage.client && wiz.getChannelForStationID(statID.intValue()) == null)
          continue;
        java.util.StringTokenizer toke2 = new java.util.StringTokenizer(sub.substring(idx + 1), "&");
        String[] nums = new String[toke2.countTokens()];
        for (int i = 0; i < nums.length; i++)
          nums[i] = toke2.nextToken();
        rv.put(statID, nums);
      }catch (NumberFormatException e){}
    }
    return rv;
  }

  private static String createChanMapString(java.util.Map chanMap)
  {
    StringBuffer sb = new StringBuffer();
    java.util.Iterator walker = chanMap.entrySet().iterator();
    while (walker.hasNext())
    {
      java.util.Map.Entry next = (java.util.Map.Entry) walker.next();
      sb.append(next.getKey());
      sb.append(',');
      sb.append(next.getValue());
      sb.append(';');
    }
    return sb.toString();
  }

  private static String createMultiChanMapString(java.util.Map chanMap)
  {
    StringBuffer sb = new StringBuffer();
    java.util.Iterator walker = chanMap.entrySet().iterator();
    while (walker.hasNext())
    {
      java.util.Map.Entry next = (java.util.Map.Entry) walker.next();
      sb.append(next.getKey());
      sb.append(',');
      String[] nums = (String[]) next.getValue();
      for (int i = 0; i < nums.length; i++)
      {
        sb.append(nums[i]);
        if (i < nums.length - 1)
          sb.append('&');
      }
      sb.append(';');
    }
    return sb.toString();
  }

  private static java.util.Map reverseMap(java.util.Map me)
  {
    java.util.Map rv = new java.util.HashMap();
    java.util.Iterator walker = me.entrySet().iterator();
    while (walker.hasNext())
    {
      java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
      rv.put(ent.getValue(), ent.getKey());
    }
    return rv;
  }

  public boolean getAutodial() { return autodial; }
  public void setAutodial(boolean x) { Sage.putBoolean(prefs + AUTODIAL, autodial = x); }

  public long getTimeTillUpdate()
  {
    long rv = Long.MAX_VALUE;
    for (int i = 0; (i < sources.size()) && alive; i++)
    {
      long currWait = ((EPGDataSource) sources.elementAt(i)).getTimeTillUpdate();
      rv = Math.min(rv, currWait);
    }
    return rv;
  }

  // This only returns enabled sources because it's used to find a provider that a channel is
  // available on when no provider has been specified
  public long[] getAllProviderIDs()
  {
    int numValid = 0;
    for (int i = 0; i < sources.size(); i++)
      if (((EPGDataSource) sources.elementAt(i)).getEnabled())
        numValid++;
    long[] rv = new long[numValid];
    int j = 0;
    for (int i = 0; i < sources.size(); i++)
    {
      EPGDataSource ds = (EPGDataSource) sources.elementAt(i);
      if (ds.getEnabled())
        rv[j++] = ds.getProviderID();
    }
    return rv;
  }

  public String[] getAllProviderNames()
  {
    String[] rv = new String[sources.size()];
    for (int i = 0; i < sources.size(); i++)
      rv[i] = ((EPGDataSource) sources.elementAt(i)).getName();
    return rv;
  }

  private void createViewableStationIDCache()
  {
    synchronized (stationIDCacheLock)
    {
      java.util.HashSet viewableStations = new java.util.HashSet();
      for (int i = 0; i < sources.size(); i++)
      {
        EPGDataSource ds = (EPGDataSource) sources.elementAt(i);
        if (mmc.isProviderUsedAndActive(ds.getProviderID()))
        {
          // To undo anything we may have done by disabling it
          ds.setEnabled(true);
          java.util.Map chanMap = (java.util.Map) lineups.get(new Long(ds.getProviderID()));
          if (chanMap != null)
          {
            java.util.Iterator walker = chanMap.entrySet().iterator();
            while (walker.hasNext())
            {
              java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
              if (!ds.unavailStations.contains(ent.getKey()))
              {
                String[] val = (String[]) ent.getValue();
                if (val.length > 0 && val[0].length() > 0)
                  viewableStations.add(ent.getKey());
              }
            }
          }
        }
        else
          ds.setEnabled(false);
      }
      viewableStationIDCache = new int[viewableStations.size()];
      java.util.Iterator walker = viewableStations.iterator();
      int i = 0;
      while (walker.hasNext())
      {
        viewableStationIDCache[i++] = ((Integer) walker.next()).intValue();
      }
      java.util.Arrays.sort(viewableStationIDCache);
    }
  }

  public boolean canViewStation(int stationID)
  {
    synchronized (stationIDCacheLock)
    {
      if (viewableStationIDCache == null)
        createViewableStationIDCache();
      return java.util.Arrays.binarySearch(viewableStationIDCache, stationID) >= 0;
    }
  }
  /*for (int i = 0; i < sources.size(); i++)
		{
			EPGDataSource ds = (EPGDataSource) sources.elementAt(i);
			if (ds.canViewStation(stationID) && getChannel(ds.getProviderID(), stationID).length() > 0 &&
				mmc.isProviderUsedAndActive(ds.getProviderID()))
				return true;
		}
		return false;
	}*/

  public String[][] getProviders(String zipCode, boolean[] didConnect)
  {
    if (hasEPGPlugin())
    {
      updateEPGPluginObj();
      if (epgImportPlugin != null)
      {
        if (didConnect != null && didConnect.length > 0)
          didConnect[0] = true;
        return epgImportPlugin.getProviders(zipCode);
      }
      else
        return new String[0][0];
    }
    else
      return WarlockRipper.getProviders(zipCode, didConnect);
  }

  public String[] getProvidersAndCacheNames(String zipCode, boolean[] didConnect)
  {
    String[][] temprv = getProviders(zipCode, didConnect);
    if (temprv == null)
      return null;
    String[] rv = new String[temprv.length];
    for (int i = 0; i < temprv.length; i++)
    {
      rv[i] = temprv[i][1];
      providerNameToIDCache.put(rv[i], temprv[i][0]);
    }
    return rv;
  }

  public String[][] getLocalMarkets(boolean[] didConnect)
  {
    if (hasEPGPlugin())
    {
      updateEPGPluginObj();
      if (epgImportPlugin != null)
      {
        if (didConnect != null && didConnect.length > 0)
          didConnect[0] = true;
        return epgImportPlugin.getLocalMarkets();
      }
      else
        return new String[0][0];
    }
    else
      return WarlockRipper.getLocalMarkets(didConnect);
  }

  public String[] getLocalMarketsAndCacheNames(boolean[] didConnect)
  {
    String[][] temprv = getLocalMarkets(didConnect);
    if (temprv == null)
      return null;
    String[] rv = new String[temprv.length];
    for (int i = 0; i < temprv.length; i++)
    {
      rv[i] = temprv[i][1];
      providerNameToIDCache.put(rv[i], temprv[i][0]);
    }
    return rv;
  }

  public boolean hasEPGPlugin() { return Sage.get(prefs + EPG_IMPORT_PLUGIN, "").length() > 0; }

  boolean pluginExtractGuide(String providerID)
  {
    updateEPGPluginObj();
    try
    {
      return epgImportPlugin.updateGuide(providerID, Wizard.getInstance());
    }
    catch (Throwable e)
    {
    	System.out.println("Error updating with EPG Plugin:");
    	Sage.printStackTrace(e);
    	return false;
    }
  }

  public long getCachedProviderIDForName(String s)
  {
    s = (String) providerNameToIDCache.get(s);
    return (s==null)?0:Long.parseLong(s);
  }

  private Wizard wiz;
  private MMC mmc;
  private String prefs;
  private String dsPrefs;
  private String accessCode;
  private String zipCode;

  private java.util.Vector sources;

  private boolean downloadWhileInactive;
  private int downloadFrequency;
  private int downloadOffset;

  private long nextDownloadTime;

  private boolean inactive;
  private boolean autodial;

  private EPGDataSource currDS;
  private boolean alive = true;
  private java.io.File logoDir;
  private java.util.Map logoMap;
  //	private java.util.Map colorMap;

  private Object stationIDCacheLock = new Object();
  private int[] viewableStationIDCache;

  private java.util.Map lineups;
  private java.util.Map lineupOverrides;
  private java.util.Map serviceLevels;

  private java.util.Map physicalLineups;
  private java.util.Map physicalLineupOverrides;

  private String epgPluginString;
  private EPGImportPlugin epgImportPlugin;

  private long epgErrorSleepTime = 60000;

  private java.util.Map providerNameToIDCache;

  public String getEpgStateString(){
    return epgState.getReportingText();
  }

  // 601 revised version
  public static final java.util.Comparator channelNumSorter = new java.util.Comparator()
  {
    public int compare(Object o1, Object o2)
    {
      int rv;
      if (o1 == o2) rv = 0;
      else if (o1 == null) rv = (1);
      else if (o2 == null) rv = (-1);
      else
      {
        String s1, s2;
        if (o1 instanceof Channel && o2 instanceof Channel)
        {
          Channel c1 = (Channel)o1;
          Channel c2 = (Channel)o2;

          s1 = UIManager.leadZero(c1.getNumber(0));
          s2 = UIManager.leadZero(c2.getNumber(0));
        }
        else
        {
          s1 = UIManager.leadZero(o1.toString());
          s2 = UIManager.leadZero(o2.toString());
        }

        rv = (s1.compareTo(s2));
      }
      //if reverse channels (but that's a UI specific property)
      //	rv *= -1;
      return rv;
    }
  };
}

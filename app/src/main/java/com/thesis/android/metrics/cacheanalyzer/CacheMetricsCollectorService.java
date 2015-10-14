package com.thesis.android.metrics.cacheanalyzer;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Created by Srinivas on 9/30/2015.
 */
public class CacheMetricsCollectorService extends Service {

    private double cacheHitRatio = -1;
    private int cacheHits = 0;
    private int cacheMisses = 0;

    private List<String> runningApplicationsList = new ArrayList<>(Arrays.asList("Loading..."));
    private String runningApplications = customStringFormatForList(runningApplicationsList);
    private String foregroundApp = "Loading...";
    private String recentCacheBehavior = "Loading...";

    private Queue<String> cacheBehavior = new LinkedList<>();

    /*
        If activity is reopened, set the cache metrics with values from DB.
        This method is run every time the activity is started.
    */

    void getCurrentMetricsFromFile()
    {
        final String filename = getString(R.string.metricsDataFileName);

        FileInputStream inputStream;
        InputStreamReader inputStreamReader;
        BufferedReader bufferedReader = null;

        try
        {
            inputStream       = openFileInput(filename);
            inputStreamReader = new InputStreamReader(inputStream);
            bufferedReader    = new BufferedReader(inputStreamReader);

            cacheHits   = Integer.valueOf(bufferedReader.readLine());
            cacheMisses = Integer.valueOf(bufferedReader.readLine());
        }
        catch (FileNotFoundException e)
        {
            Log.d("Exception", "File hasn't been created yet, method called for first time\n\n\n\n\n\n\n\n\n\n\n\n");
            return;
        }
        catch (IOException e)
        {
            Log.d("Exception", "IO Exception while reading from metrics file\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");
            e.printStackTrace();
        }
        finally
        {
            try
            {   if(bufferedReader != null)
                    closeFileInputHandlers(bufferedReader);
            }
            catch (IOException e)
            {
                Log.d("Exception", "Exception occurred while closing read file handler\n\n\n\n\n\n\n\n\n\n\n\n");
            }
        }
    }

    private void closeFileInputHandlers(BufferedReader bufferedReader) throws IOException
    {
        bufferedReader.close();
    }

    void updateMetricsFile()
    {
        final String filename = getString(R.string.metricsDataFileName);
        final String hits     = String.valueOf(cacheHits);
        final String misses   = String.valueOf(cacheMisses);

        FileOutputStream outputStream;
        OutputStreamWriter outputStreamWriter;
        BufferedWriter bufferedWriter = null;

        try
        {
            outputStream         = openFileOutput(filename, Context.MODE_PRIVATE);
            outputStreamWriter   = new OutputStreamWriter(outputStream);
            bufferedWriter       = new BufferedWriter(outputStreamWriter);

            bufferedWriter.write(hits);
            bufferedWriter.newLine();
            bufferedWriter.write(misses);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.d("Exception", "Exception occurred while writing to file\n\n\n\n\n\n\n\n\n\n\n\n\n");
        }
        finally
        {
            try {
                if(bufferedWriter != null)
                    closeFileOutputHandlers(bufferedWriter);
            } catch (IOException e) {
                Log.d("Exception", "Exception occurred while closing file handler\n\n\n\n\n\n\n\n\n\n\n\n\n")
                e.printStackTrace();
            }
        }
    }

    private void closeFileOutputHandlers(BufferedWriter bufferedWriter) throws IOException
    {
        bufferedWriter.flush();
        bufferedWriter.close();
    }

    private String computeForegroundApp()
    {
        File[] files = new File(getString(R.string.topLevelFilePath)).listFiles();
        int lowestOomScore = Integer.MAX_VALUE;
        String foregroundProcess = null;

        for (File file : files) {
            if (!file.isDirectory()) {
                continue;
            }

            int pid;
            try {
                pid = Integer.parseInt(file.getName());
            } catch (NumberFormatException e) {
                continue;
            }

            try {
                String cgroup = read(String.format(getString(R.string.cgroupFilePath), pid));

                String[] lines = cgroup.split(getString(R.string.EOL));

                if (lines.length != 2) {
                    continue;
                }

                String cpuSubsystem = lines[0];
                String cpuaccctSubsystem = lines[1];

                if (!cpuaccctSubsystem.endsWith(Integer.toString(pid))) {
                    // not an application process
                    continue;
                }

                if (cpuSubsystem.endsWith(getString(R.string.bg_non_interactive))) {
                    // background policy
                    continue;
                }

                String cmdline = read(String.format(getString(R.string.cmdlineFilePath), pid));

                if (cmdline.contains(getString(R.string.systemUIProcessName))) {
                    continue;
                }

                int uid = Integer.parseInt(
                        cpuaccctSubsystem.split(getString(R.string.COLON))[2].split(getString(R.string.FORWARD_SLASH))[1]
                                .replace(getString(R.string.uid_), getString(R.string.EMPTY)));
                if (uid >= 1000 && uid <= 1038) {
                    // system process
                    continue;
                }

                int appId = uid - AID_APP;
                int userId = 0;
                // loop until we get the correct user id.
                // 100000 is the offset for each user.
                while (appId > AID_USER) {
                    appId -= AID_USER;
                    userId++;
                }

                if (appId < 0) {
                    continue;
                }

                // u{user_id}_a{app_id} is used on API 17+ for multiple user account support.
                // String uidName = String.format("u%d_a%d", userId, appId);

                File oomScoreAdj = new File(String.format(getString(R.string.oomScoreAdjFilePath), pid));
                if (oomScoreAdj.canRead()) {
                    int oomAdj = Integer.parseInt(read(oomScoreAdj.getAbsolutePath()));
                    if (oomAdj != 0) {
                        continue;
                    }
                }

                int oomscore = Integer.parseInt(read(String.format(getString(R.string.oomScoreFilePath), pid)));
                if (oomscore < lowestOomScore) {
                    lowestOomScore = oomscore;
                    foregroundProcess = cmdline;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String result = null;

        if(foregroundProcess != null)
            result = foregroundProcess.replaceAll("[^a-z.]+", "");

        return result;
    }

    private List<String> computeRunningApplicationsList()
    {
        List<String> runningApplicationsNames = new ArrayList<>();

        List<ProcessManager.Process> runningApplications = ProcessManager.getRunningApplications();
        Collections.sort(runningApplications);

        for(ProcessManager.Process process : runningApplications)
        {
            String processName = process.name;
            runningApplicationsNames.add(removeColonsUnderscoresAndDigits(processName));
        }

        return runningApplicationsNames;
    }

    void updateStatistics()
    {
        Set<String> appsThatDontAddToCacheMetrics = new HashSet<>();
        addIrrelevantAppsToSet(appsThatDontAddToCacheMetrics);

        String newForegroundApp = computeForegroundApp();

        /*
            User is using the recent apps screen to pick a foreground application.
            In this case, newForegroundApp is null
        */
        if(newForegroundApp == null)
            return;

        final boolean foregroundAppChanged = !newForegroundApp.equals(foregroundApp);

        /*
            User has switched to a new application, update Cache statistics!

            The second check in the if statement ensures that switching to
            the home screen (internally a process or switching to the Cache
            Metrics app doesn't affect the statistics.)
        */

        if(foregroundAppChanged && !appsThatDontAddToCacheMetrics.contains(newForegroundApp))
        {
            removeLastIfQueueFull();

            if (runningApplicationsList.contains(newForegroundApp)) {
                Log.d(getString(R.string.foregroundTag), getString(R.string.cacheHit));
                cacheBehavior.offer(getString(R.string.hit) + getString(R.string.EOLOld) + foregroundApp + getString(R.string.EOLNew)
                        + newForegroundApp + getString(R.string.EOL));
                cacheHits++;
            } else
            {
                Log.d(getString(R.string.foregroundTag), getString(R.string.cacheMiss));
                cacheBehavior.offer(getString(R.string.miss) + getString(R.string.EOLOld) + foregroundApp + getString(R.string.EOLNew)
                        + newForegroundApp + getString(R.string.EOL));
                cacheMisses++;
            }

        }

        cacheHitRatio = (cacheHits + cacheMisses) < 1 ? 0.0 : cacheHits * 100 / (cacheHits + cacheMisses);
        foregroundApp = newForegroundApp;

        recentCacheBehavior     = customStringFormatForQueue(cacheBehavior);
        runningApplicationsList = computeRunningApplicationsList();
        runningApplications     = customStringFormatForList(runningApplicationsList);
    }

    private void addIrrelevantAppsToSet(Set<String> appsThatDontAddToCacheMetrics) {
        appsThatDontAddToCacheMetrics.add(getString(R.string.myApp));
        appsThatDontAddToCacheMetrics.add(getString(R.string.homeScreen));
        appsThatDontAddToCacheMetrics.add(getString(R.string.acoreProcess));
        appsThatDontAddToCacheMetrics.add(getString(R.string.boxSearchApp));
    }

    private String removeColonsUnderscoresAndDigits(String inputString)
    {
        inputString = inputString.replace(":", "");
        inputString = inputString.replace("_", "");
        inputString = inputString.replace("0", "");
        inputString = inputString.replace("1", "");

        return inputString;
    }

    private void removeLastIfQueueFull() {
        if(cacheBehavior.size() > 9)
            cacheBehavior.poll();
    }

    private String customStringFormatForQueue(Queue<String> cacheBehavior)
    {
        StringBuilder result = new StringBuilder();

        for(String hitOrMiss : cacheBehavior)
            result.append(hitOrMiss).append(getString(R.string.EOL));

        return result.toString();
    }

    String getCacheHitRatio() {
        return cacheHitRatio <= 0.0 ? "N/A" : String.valueOf(cacheHitRatio) + "%";
    }

    String getCacheHits() {
        return String.valueOf(cacheHits);
    }

    String getCacheMisses() {
        return String.valueOf(cacheMisses);
    }

    public String getRecentCacheBehavior() {
        return recentCacheBehavior;
    }

    public class MyBinder extends Binder {
        CacheMetricsCollectorService getService() {
            return CacheMetricsCollectorService.this;
        }
    }

    private IBinder mBinder = new MyBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat(getString(R.string.dateTimeFormat));
        return sdf.format(new Date());
    }

    private String getListOfRunningServices()
    {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> runningServices
                = activityManager.getRunningServices(15);
        List<String> runningServicesNames = new ArrayList<>();
        for(ActivityManager.RunningServiceInfo runningService : runningServices)
        {
            runningServicesNames.add(runningService.process);
        }

        Collections.sort(runningServicesNames);
        return customStringFormatForList(runningServicesNames);
    }

    private String customStringFormatForList(List<String> list)
    {
        StringBuilder builder = new StringBuilder("");
        for(String item : list)
            builder.append(item).append("\n");
        return builder.toString();
    }

    private String applyFilters(String item) {
        item = item.replaceAll("com.", "");
        item = item.replaceAll("android.", "");
        item = item.replaceAll("google.", "");
        item = item.replace('.', '-');
        item = item.toUpperCase();
        return item;
    }

    private String getAllInstalledApplications()
    {
        final PackageManager packageManager = getPackageManager();
        List<ApplicationInfo> packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        List<String> packageNames = new ArrayList<>();
        for(ApplicationInfo pkg : packages)
            packageNames.add(pkg.packageName);
        Collections.sort(packageNames);
        return customStringFormatForList(packageNames);
    }

    private String getRunningProcesses()
    {
        List<String> runningProcessesNames = new ArrayList<>();

        List<ProcessManager.Process> runningProcesses = ProcessManager.getRunningProcesses();
        for(ProcessManager.Process process : runningProcesses)
            runningProcessesNames.add(process.name);

        return customStringFormatForList(runningProcessesNames);
    }

    String getRunningApplications()
    {
        return runningApplications;
    }

    List<String> getRunningApplicationsList()
    {
        return runningApplicationsList;

    }

    /** first app user */
    public static final int AID_APP = 10000;

    /** offset for uid ranges for each user */
    public static final int AID_USER = 100000;

    String getForegroundApp()
    {
        return foregroundApp;
    }

    private static String read(String path) throws IOException {
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(path));
        output.append(reader.readLine());
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            output.append('\n').append(line);
        }
        reader.close();
        return output.toString();
    }
}

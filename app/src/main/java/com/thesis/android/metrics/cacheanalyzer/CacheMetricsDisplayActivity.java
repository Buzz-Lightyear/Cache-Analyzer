package com.thesis.android.metrics.cacheanalyzer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.widget.TextView;

public class CacheMetricsDisplayActivity extends AppCompatActivity {


    CacheMetricsCollectorService mService;
    boolean mServiceBound = false;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CacheMetricsCollectorService.MyBinder myBinder
                    = (CacheMetricsCollectorService.MyBinder) service;
            mService = myBinder.getService();
            mServiceBound = true;

            Thread t = new Thread() {

                @Override
                public void run() {
                    try {

                        /*
                            If the activity is closed or hung for some reason and users end up
                            reopening it, we need to get the previous values from the database.

                            Initially the values in the file are 0 hits and 0 misses.
                        */

                        mService.getCurrentMetricsFromFile();

                        while (!isInterrupted()) {
                            Thread.sleep(1000);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    populateTextViews();
                                    mService.updateStatistics();
                                }

                                private void populateTextViews() {
                                    populateTextView(mService.getCacheHits(), R.id.cacheHits);
                                    populateTextView(mService.getCacheMisses(), R.id.cacheMisses);
                                    populateTextView(mService.getCacheHitRatio(), R.id.cacheHitRatio);
                                    populateTextView(mService.getRecentCacheBehavior(), R.id.recentCacheBehavior);
                                    populateTextView(mService.getRunningApplications(), R.id.runningApplications);
                                }

                                private void populateTextView(String content, int viewId) {
                                    TextView textView = (TextView) findViewById(viewId);
                                    textView.setText(content);
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                    }
                }
            };

            t.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_metrics_display);

        Intent intent = new Intent(this, CacheMetricsCollectorService.class);
        startService(intent);
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        Log.d("Main Activity", "Service is being destroyed");

        if(mServiceBound)
        {
            /*
                If the activity is closed for some reason, update the database.
                When the service restarts and the activity opens, it'll have the updated values.
            */
            mService.updateMetricsFile();

            unbindService(mServiceConnection);
            mServiceBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_cache_metrics_display, menu);
        return true;
    }

}

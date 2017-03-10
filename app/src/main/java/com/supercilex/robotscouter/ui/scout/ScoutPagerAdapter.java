package com.supercilex.robotscouter.ui.scout;

import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.LinearLayout;

import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.supercilex.robotscouter.R;
import com.supercilex.robotscouter.RobotScouter;
import com.supercilex.robotscouter.data.util.ScoutUtils;
import com.supercilex.robotscouter.data.util.TeamHelper;
import com.supercilex.robotscouter.util.ConnectivityHelper;
import com.supercilex.robotscouter.util.Constants;

import java.util.ArrayList;
import java.util.List;

public class ScoutPagerAdapter extends FragmentStatePagerAdapter
        implements ValueEventListener, TabLayout.OnTabSelectedListener, View.OnLongClickListener {
    private final ValueEventListener mTabNameListener = new ValueEventListener() {
        @Override
        public void onDataChange(DataSnapshot snapshot) {
            int tabIndex = mKeys.indexOf(snapshot.getRef().getParent().getKey());
            TabLayout.Tab tab = mTabLayout.getTabAt(tabIndex);
            tab.setText(snapshot.getValue() == null ?
                                getPageTitle(tabIndex) : snapshot.getValue(String.class));
            View tabView = ((LinearLayout) mTabLayout.getChildAt(0)).getChildAt(tabIndex);
            tabView.setOnLongClickListener(ScoutPagerAdapter.this);
            tabView.setId(tabIndex);
        }

        @Override
        public void onCancelled(DatabaseError error) {
            ScoutPagerAdapter.this.onCancelled(error);
        }
    };

    private List<String> mKeys = new ArrayList<>();
    private String mCurrentScoutKey;
    private Query mQuery;

    private AppCompatActivity mActivity;
    private TeamHelper mTeamHelper;
    private TabLayout mTabLayout;

    public ScoutPagerAdapter(AppCompatActivity activity,
                             TabLayout tabLayout,
                             TeamHelper helper,
                             String currentScoutKey) {
        super(activity.getSupportFragmentManager());
        mActivity = activity;
        mTabLayout = tabLayout;
        mTeamHelper = helper;
        mCurrentScoutKey = currentScoutKey;
        mQuery = ScoutUtils.getIndicesRef(helper.getTeam().getKey());
        mQuery.addValueEventListener(this);
    }

    @Override
    public Fragment getItem(int position) {
        return ScoutFragment.newInstance(mQuery.getRef().getKey(), mKeys.get(position));
    }

    @Override
    public int getCount() {
        return mKeys.size();
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return "Scout " + (getCount() - position);
    }

    public String getCurrentScoutKey() {
        return mCurrentScoutKey;
    }

    public void setCurrentScoutKey(String currentScoutKey) {
        mCurrentScoutKey = currentScoutKey;
    }

    @Override
    public void onTabSelected(TabLayout.Tab tab) {
        mCurrentScoutKey = mKeys.get(tab.getPosition());
    }

    @Override
    public void onDataChange(DataSnapshot snapshot) {
        removeNameListeners();
        boolean hadScouts = !mKeys.isEmpty();
        mKeys.clear();
        for (DataSnapshot scoutIndex : snapshot.getChildren()) {
            String key = scoutIndex.getKey();
            mKeys.add(0, key);
            getTabNameRef(key).addValueEventListener(mTabNameListener);
        }
        if (hadScouts && mKeys.isEmpty() && !ConnectivityHelper.isOffline(mActivity)) {
            ShouldDeleteTeamDialog.show(mActivity.getSupportFragmentManager(), mTeamHelper);
        }
        mActivity.findViewById(R.id.empty_list_hint)
                .setVisibility(mKeys.isEmpty() ? View.VISIBLE : View.GONE);

        mTabLayout.removeOnTabSelectedListener(this);
        notifyDataSetChanged();
        mTabLayout.addOnTabSelectedListener(this);

        if (!mKeys.isEmpty()) {
            if (mCurrentScoutKey == null) {
                selectTab(0);
                mCurrentScoutKey = mKeys.get(0);
            } else {
                selectTab(mKeys.indexOf(mCurrentScoutKey));
            }
        }
    }

    private void selectTab(int index) {
        TabLayout.Tab tab = mTabLayout.getTabAt(index);
        if (tab != null) tab.select();
    }

    private DatabaseReference getTabNameRef(String key) {
        return Constants.FIREBASE_SCOUTS.child(key).child(Constants.FIREBASE_NAME);
    }

    public void cleanup() {
        mQuery.removeEventListener(this);
        removeNameListeners();
        RobotScouter.getRefWatcher(mActivity).watch(this);
    }

    private void removeNameListeners() {
        for (String key : mKeys) getTabNameRef(key).removeEventListener(mTabNameListener);
    }

    @Override
    public boolean onLongClick(View v) {
        ScoutNameDialog.show(
                mActivity.getSupportFragmentManager(),
                Constants.FIREBASE_SCOUTS.child(mKeys.get(v.getId()))
                        .child(Constants.FIREBASE_NAME),
                mTabLayout.getTabAt(v.getId()).getText().toString());
        return true;
    }

    @Override
    public void onCancelled(DatabaseError error) {
        FirebaseCrash.report(error.toException());
    }

    @Override
    public void onTabUnselected(TabLayout.Tab tab) {
        // Not interesting
    }

    @Override
    public void onTabReselected(TabLayout.Tab tab) {
        // Not interesting
    }
}

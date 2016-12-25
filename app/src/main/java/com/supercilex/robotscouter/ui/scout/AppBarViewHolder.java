package com.supercilex.robotscouter.ui.scout;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.graphics.Palette;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.supercilex.robotscouter.R;
import com.supercilex.robotscouter.data.model.Team;
import com.supercilex.robotscouter.ui.AppCompatBase;

public class AppBarViewHolder {
    private Team mTeam;

    private AppCompatBase mActivity;
    private CollapsingToolbarLayout mHeader;
    private ImageView mBackdrop;

    private MenuItem mActionVisitTeamWebsite;

    public AppBarViewHolder(AppCompatBase activity) {
        mActivity = activity;
        mHeader = (CollapsingToolbarLayout) activity.findViewById(R.id.header);
        mBackdrop = (ImageView) activity.findViewById(R.id.backdrop);
    }

    public void bind(@NonNull Team team) {
        if (mTeam != null && mTeam.equals(team)) return;
        mTeam = team;
        mActivity.getSupportActionBar().setTitle(mTeam.getFormattedName());
        setTaskDescription(null, ContextCompat.getColor(mActivity, R.color.color_primary));
        loadImages();
        bindMenu();
    }

    private void loadImages() {
        Glide.with(mActivity)
                .load(mTeam.getMedia())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(R.drawable.ic_android_black_24dp)
                .into(mBackdrop);

        Glide.with(mActivity)
                .load(mTeam.getMedia())
                .asBitmap()
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(final Bitmap bitmap,
                                                GlideAnimation glideAnimation) {
                        if (bitmap != null && !bitmap.isRecycled()) {
                            Palette.from(bitmap).generate(new Palette.PaletteAsyncListener() {
                                @Override
                                public void onGenerated(Palette palette) {
                                    Palette.Swatch vibrantSwatch = palette.getVibrantSwatch();
                                    if (vibrantSwatch != null) {
                                        int opaque = vibrantSwatch.getRgb();
                                        mHeader.setStatusBarScrimColor(opaque);
                                        mHeader.setContentScrimColor(getTransparentColor(opaque));
                                        setTaskDescription(bitmap, opaque);
                                    }
                                }
                            });
                        }
                    }
                });
    }

    private void setTaskDescription(Bitmap icon, @ColorInt int colorPrimary) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mActivity.setTaskDescription(
                    new ActivityManager.TaskDescription(mTeam.getFormattedName(),
                                                        icon,
                                                        colorPrimary));
        }
    }

    public void initMenu(Menu menu) {
        menu.findItem(R.id.action_visit_tba_team_website).setTitle(
                String.format(mActivity.getString(R.string.menu_item_visit_team_website_on_tba),
                              mTeam.getNumber()));

        mActionVisitTeamWebsite = menu.findItem(R.id.action_visit_team_website);
        mActionVisitTeamWebsite.setTitle(
                String.format(mActivity.getString(R.string.menu_item_visit_team_website),
                              mTeam.getNumber()));
        bindMenu();
    }

    private void bindMenu() {
        if (mActionVisitTeamWebsite != null) {
            mActionVisitTeamWebsite.setVisible(mTeam.getWebsite() != null);
        }
    }

    private int getTransparentColor(@ColorInt int opaque) {
        return Color.argb(Math.round(Color.alpha(opaque) * 0.6f),
                          Color.red(opaque),
                          Color.green(opaque),
                          Color.blue(opaque));
    }
}

package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional offline game shelf. All streaming and pairing remain owned by Moonlight. */
final class MoonlightShelf {
    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final View hero;
    private final View glass;
    private final File directory;
    private final float density;
    private final boolean dockRow;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::position;
    private final List<TextView> cards = new ArrayList<>();
    private final List<ImageView> covers = new ArrayList<>();
    private final List<Bitmap> bitmaps = new ArrayList<>();
    private LinearLayout panel;
    private HorizontalScrollView scroll;
    private LinearLayout row;
    private TextView selectedName;
    private TextView library;
    private boolean lowerApps;
    private boolean returningFromGame;
    private GlassView gameGlass;
    private androidx.dynamicanimation.animation.SpringAnimation gameSpringX, gameSpringY;
    private boolean animateGames;
    private float gameScale;
    private TextView pcChoice;
    private WolfSessionMenu sessionMenu;
    private boolean resumed;
    private android.app.AlertDialog hostDialog;
    private List<String> hostNames = new ArrayList<>();
    private List<String> hostUuids = new ArrayList<>();
    private String pickedName;
    private String pickedUuid;
    private MoonlightCatalog catalog;
    private int selected;
    private String activeGameId;
    private boolean busy;
    private boolean destroyed;
    private long loadedStamp = Long.MIN_VALUE;
    private int panelHeight;
    private int tileWidth;
    private boolean fallback;
    private TextView fallbackCard;
    private View fallbackTile;
    private TextView moving;
    private long clickGuard;
    private TextView activePlay;
    private ObjectAnimator activePulse;
    private final List<ObjectAnimator> wobbles = new ArrayList<>();
    private View lastDock;
    private final android.graphics.drawable.Drawable originalBackground;

    MoonlightShelf(Activity activity, FrameLayout root, RecyclerView grid, View hero, View glass) {
        this.activity=activity; this.root=root; this.grid=grid; this.hero=hero; this.glass=glass;
        density=activity.getResources().getDisplayMetrics().density;
        dockRow=Config.load(activity).dockRow;
        originalBackground=root.getBackground();
        File external=activity.getExternalFilesDir(null);
        directory=external==null ? null : new File(external,"moonlight");
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    void resume() {
        resumed=true;
        if(!enabled() || !moonlightInstalled()) {
            loadedStamp=Long.MIN_VALUE;
            if(panel!=null)removePanel();
            return;
        }
        if(sessionMenu!=null && hasGameCatalog())sessionMenu.resume();
        if (destroyed || busy || directory==null) return;
        final long stamp=new File(directory,"catalog.json").lastModified();
        if (stamp==loadedStamp) { restoreAfterReturn();position();return; }
        busy=true;
        loader.execute(()-> {
            MoonlightCatalog result=null;
            List<Bitmap> images=new ArrayList<>();
            List<String> names=new ArrayList<>();
            List<String> uuids=new ArrayList<>();
            try {
                File source=MoonlightCatalog.localFile(directory,"catalog.json");
                JSONObject json=new JSONObject(read(source));
                if(json.getInt("schema_version")!=1)throw new IOException("Unsupported catalog");
                JSONArray entries=json.getJSONArray("games");
                if(entries.length()>MoonlightCatalog.MAX_GAMES)throw new IOException("Too many games");
                List<MoonlightCatalog.Game> games=new ArrayList<>();
                for(int i=0;i<entries.length();i++) {
                    JSONObject entry=entries.getJSONObject(i);
                    games.add(new MoonlightCatalog.Game(entry.getString("name"),entry.optString("app_id",null),entry.optString("image",null)));
                }
                result=new MoonlightCatalog(json.getString("host_name"),json.getString("host_uuid"),games);
                names.add(result.hostName);uuids.add(result.hostUuid);
                JSONArray hosts=json.optJSONArray("hosts");
                if(hosts!=null && hosts.length()>8)throw new IOException("Too many hosts");
                if(hosts!=null)for(int i=0;i<hosts.length();i++) {
                    JSONObject host=hosts.getJSONObject(i);
                    MoonlightCatalog validated=new MoonlightCatalog(host.getString("name"),host.getString("uuid"),games);
                    if(!uuids.contains(validated.hostUuid)) {names.add(validated.hostName);uuids.add(validated.hostUuid);}
                }
                long remaining=12L*1024*1024;
                for(MoonlightCatalog.Game game:result.games) {
                    if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");
                    Bitmap image=remaining>0 ? cover(game.image) : null;
                    if(image!=null) {
                        if(image.getAllocationByteCount()>remaining) {image.recycle();image=null;}
                        else remaining-=image.getAllocationByteCount();
                    }
                    images.add(image);
                }
            } catch(Exception ignored) {result=null;}
            final MoonlightCatalog ready=result;
            main.post(()-> {
                busy=false;
                if(destroyed) {recycle(images);return;}
                loadedStamp=stamp;
                removePanel();
                if(!enabled() || !moonlightInstalled()) {loadedStamp=Long.MIN_VALUE;recycle(images);return;}
                if(ready==null) {recycle(images);showFallback();return;}
                catalog=ready;hostNames=names;hostUuids=uuids;bitmaps.addAll(images);build();
            });
        });
    }

    private static String read(File file) throws IOException {
        if(!file.isFile() || file.length()>MoonlightCatalog.MAX_CATALOG_BYTES)throw new IOException("Catalog unavailable");
        try(FileInputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1) {
                if(out.size()+n>MoonlightCatalog.MAX_CATALOG_BYTES)throw new IOException("Catalog too large");
                out.write(buffer,0,n);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private Bitmap cover(String name) {
        if(name==null)return null;
        try {
            File file=MoonlightCatalog.localFile(directory,name);
            if(!file.isFile() || file.length()>4L*1024*1024)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getPath(),options);
            if(options.outWidth<1 || options.outHeight<1 || options.outWidth>8192 || options.outHeight>8192)return null;
            options.inSampleSize=1;
            while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>512)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            return BitmapFactory.decodeFile(file.getPath(),options);
        } catch(IOException ignored) {return null;}
    }

    private void build() {
        String saved=activity.getSharedPreferences("moonlight",0).getString("game_name","");
        selected=0;
        for(int i=0;i<catalog.games.size();i++)if(catalog.games.get(i).name.equals(saved))selected=i;
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);panel.setClipToPadding(false);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(220));
        pp.leftMargin=dp(60);pp.rightMargin=dp(60);pp.topMargin=dp(118);
        root.addView(panel,root.indexOfChild(glass),pp);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        pcChoice=button("PC: Wolf ▾");pcChoice.setId(View.generateViewId());
        pcChoice.setOnClickListener(v->chooseHost());
        pcChoice.setOnKeyListener((v,key,event)-> {
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_UP) {activity.findViewById(R.id.btnInputs).requestFocusFromTouch();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_DOWN) {focusGames();return true;}
            return false;
        });
        heading.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        heading.addView(pcChoice,new LinearLayout.LayoutParams(dp(170),dp(28)));
        panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(36)));
        scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setClipChildren(false);
        scroll.setPadding(dp(5),dp(5),dp(5),dp(5));scroll.setFillViewport(false);scroll.setFocusable(false);
        row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setClipChildren(false);
        scroll.addView(row,new ViewGroup.LayoutParams(-2,-1));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<catalog.games.size();i++) {
            final int index=i;
            MoonlightCatalog.Game game=catalog.games.get(i);
            FrameLayout tile=new FrameLayout(activity);
            tile.setClipChildren(false);
            ImageView cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setBackground(shape(0xff242126,0));cover.setClipToOutline(true);
            if(bitmaps.get(i)!=null)cover.setImageBitmap(bitmaps.get(i));
            tile.addView(cover,new FrameLayout.LayoutParams(-1,-1));covers.add(cover);
            TextView card=text(bitmaps.get(i)==null?game.name:"",14,Color.WHITE);
            card.setGravity(Gravity.CENTER);card.setPadding(dp(8),dp(8),dp(8),dp(8));
            card.setBackground(shape(Color.TRANSPARENT,0));card.setFocusable(true);card.setClickable(true);card.setId(View.generateViewId());card.setContentDescription(game.name);
            tile.addView(card,new FrameLayout.LayoutParams(-1,-1));cards.add(card);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(125),-1);lp.rightMargin=dp(12);row.addView(tile,lp);
            card.setOnFocusChangeListener((v,focused)-> {
                card.setBackground(shape(Color.TRANSPARENT,0));
                float scale=focused?gameScale:1f;
                if(animateGames)tile.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
                else {tile.setScaleX(scale);tile.setScaleY(scale);}
                if(focused) {
                    selected=index;selectedName.setText(game.name);
                    activity.getSharedPreferences("moonlight",0).edit().putString("game_name",game.name).apply();
                    glass.animate().cancel();glass.setAlpha(0f);
                    scroll.smoothScrollTo(Math.max(0,tile.getLeft()-dp(8)),0);
                    card.post(()->showGameGlass(card));
                } else card.post(()-> {if(gameGlass!=null && !isInside(scroll,activity.getCurrentFocus()))gameGlass.setAlpha(0f);});
            });
            card.setOnClickListener(v-> {if(moving==null && SystemClock.uptimeMillis()>clickGuard)launch(index);});
            card.setOnLongClickListener(v-> {startMove(index);return true;});
            card.setOnKeyListener((v,key,event)-> {
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(moving!=null)return event.getRepeatCount()>0 || moveKey(key);
                int place=row==null?-1:row.indexOfChild(tile);
                if(key==KeyEvent.KEYCODE_DPAD_UP) {if(pcChoice!=null)pcChoice.requestFocusFromTouch();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_DOWN) {focusDock();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_LEFT && place==0)return true;
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT && row!=null && place==row.getChildCount()-1)return true;
                return false;
            });
        }
        selectedName=text(catalog.games.get(0).name+"  ·  via Moonlight",12,0xffd9d2d4);selectedName.setSingleLine(true);selectedName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedName.setPadding(dp(5),dp(2),0,0);
        selectedName.setVisibility(View.GONE);
        panel.addView(selectedName,new LinearLayout.LayoutParams(-1,dp(22)));
        library=button("Abrir biblioteca");library.setOnClickListener(v->launch(selected));
        library.setOnKeyListener((v,key,event)-> {
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_UP){pcChoice.requestFocusFromTouch();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusDock();return true;}
            return false;
        });
        panel.addView(library,new LinearLayout.LayoutParams(dp(160),dp(30)));
        Config cfg=Config.load(activity);animateGames=cfg.animations&&!cfg.reducedMotion;gameScale=animateGames?1.04f:1f;
        gameGlass=new GlassView(activity,null);gameGlass.setStyle(cfg.glassStyle);gameGlass.setAlpha(0f);
        gameGlass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(gameGlass,root.indexOfChild(glass),new FrameLayout.LayoutParams(dp(10),dp(10)));
        gameSpringX=new androidx.dynamicanimation.animation.SpringAnimation(gameGlass,androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X);
        gameSpringY=new androidx.dynamicanimation.animation.SpringAnimation(gameGlass,androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y);
        for(androidx.dynamicanimation.animation.SpringAnimation spring:new androidx.dynamicanimation.animation.SpringAnimation[]{gameSpringX,gameSpringY}) {
            spring.setSpring(new androidx.dynamicanimation.animation.SpringForce().setStiffness(440f).setDampingRatio(cfg.focusOvershoot?.82f:1f));
        }
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)-> {View focused=activity.getCurrentFocus();if(isInside(scroll,focused))showGameGlass(focused);});
        sessionMenu=new WolfSessionMenu(activity,catalog.hostUuid,new WolfSessionMenu.Listener() {
            @Override public void launch(String id,String name) {launchControlled(id,name);}
            @Override public void focusCarousel() {if(resumed && hasGameCatalog())focusGames();}
            @Override public void sessionState(WolfSession session) {showActiveGame(session);}
        });
        hero.setVisibility(View.INVISIBLE);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff310500,Color.BLACK}));
        grid.requestLayout();
        if(!lowerApps)parkDock(); // only a focused tile parks the dock; cold start focuses the shelf
        applyHost();
        orderViews();
        position();
        restoreAfterReturn();
    }

    private TextView text(String value,int size,int color) {
        TextView view=new TextView(activity);view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);view.setIncludeFontPadding(false);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setFontFeatureSettings("kern");return view;
    }
    private TextView button(String name) {
        TextView button=text(name,12,0xff171419);button.setGravity(Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setBackground(shape(Color.WHITE,0));
        button.setOnFocusChangeListener((v,focused)-> {
            button.setBackground(shape(focused?0xffc8dfff:Color.WHITE,focused?0xff8abaff:0));
            if(focused){glass.animate().cancel();glass.setAlpha(0f);}
        });return button;
    }
    private GradientDrawable shape(int color,int stroke) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));if(stroke!=0)d.setStroke(dp(2),stroke);return d;
    }
    private int dp(float value) {return Math.round(value*density);}
    // scrollToPosition(0) is a no-op here: with clipToPadding off the dock counts as visible while scrolled up into the padding.
    private void parkDock() {((androidx.recyclerview.widget.LinearLayoutManager)grid.getLayoutManager()).scrollToPositionWithOffset(0,0);}

    private void position() {
        if(panel==null || root.getHeight()==0)return;
        if(lowerApps){panel.setVisibility(View.INVISIBLE);if(gameGlass!=null)gameGlass.setAlpha(0f);return;}
        if(grid.getHeight()==0)return;
        // MainActivity grows the grid's top padding for the shelf after the tiles are laid out, which leaves the dock
        // above its padding line until a dock tile is focused; park it here the same way onTileFocused does.
        if(dockRow && !isInside(grid,activity.getCurrentFocus())) {
            View dockItem=grid.getLayoutManager()==null?null:grid.getLayoutManager().findViewByPosition(0);
            if(dockItem!=null) {
                int dy=dockItem.getTop()-dp(6)-grid.getPaddingTop();
                if(Math.abs(dy)>dp(8))grid.scrollBy(0,dy);
            }
        }
        // The dock target is stable even while RecyclerView recycles or animates its children.
        // DockDecoration adds 6dp above item_app's 8dp top padding.
        Rect dock=new Rect(0,0,grid.getWidth(),grid.getHeight());root.offsetDescendantRectToMyCoords(grid,dock);
        dock.top+=grid.getPaddingTop()+dp(14);
        Rect header=new Rect();View block=activity.findViewById(R.id.leftBlock);header.set(0,0,block.getWidth(),block.getHeight());root.offsetDescendantRectToMyCoords(block,header);
        int top=header.bottom+dp(12);
        int bottom=dock.top-dp(28);
        int height=bottom-top;
        // Scrolling the app grid past its dock gives the grid all available space.
        boolean show=height>=dp(145);
        panel.setVisibility(show?View.VISIBLE:View.INVISIBLE);
        if(!show && gameGlass!=null)gameGlass.setAlpha(0f);
        if(!show)return;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
        if(lp.topMargin!=top || lp.height!=height){lp.topMargin=top;lp.height=height;panel.setLayoutParams(lp);}
        if(panelHeight!=height) {
            panelHeight=height;
            tileWidth=Math.max(dp(72),Math.round((height-dp(68))*.75f));
            applyTileWidths();
            if(fallbackTile!=null) {ViewGroup.LayoutParams tp=fallbackTile.getLayoutParams();if(tp.width!=tileWidth){tp.width=tileWidth;fallbackTile.setLayoutParams(tp);}}
        }
        if(returningFromGame && hasGameCatalog()){returningFromGame=false;cards.get(selected).post(()->cards.get(selected).requestFocusFromTouch());}
    }

    private void restoreAfterReturn() {
        if(panel==null)return;
        if(returningFromGame || activity.getSharedPreferences("moonlight",0).getBoolean("return_to_games",false)) {
            returningFromGame=true;lowerApps=false;
            activity.getSharedPreferences("moonlight",0).edit().putBoolean("return_to_games",false).apply();
            parkDock();grid.requestLayout();panel.setVisibility(View.VISIBLE);panel.post(this::position);
        }
    }
    private void showGameGlass(View card) {
        if(gameGlass==null || !card.isFocused() || panel.getVisibility()!=View.VISIBLE)return;
        Rect rect=new Rect(0,0,card.getWidth(),card.getHeight());root.offsetDescendantRectToMyCoords(card,rect);
        int pad=dp(6);float w=card.getWidth()*gameScale+2*pad,h=card.getHeight()*gameScale+2*pad;
        ViewGroup.LayoutParams lp=gameGlass.getLayoutParams();
        if(lp.width!=(int)w || lp.height!=(int)h){lp.width=(int)w;lp.height=(int)h;gameGlass.setLayoutParams(lp);}
        gameGlass.setRadius(dp(8)*gameScale);
        float x=rect.exactCenterX()-w/2,y=rect.exactCenterY()-h/2;
        if(animateGames && gameGlass.getAlpha()>0f){gameSpringX.animateToFinalPosition(x);gameSpringY.animateToFinalPosition(y);}
        else {gameSpringX.cancel();gameSpringY.cancel();gameGlass.setTranslationX(x);gameGlass.setTranslationY(y);}
        gameGlass.setAlpha(1f);glass.animate().cancel();glass.setAlpha(0f);
    }

    boolean active() { return panel != null && (catalog != null || fallback); }

    boolean moonlightInstalled() {
        try {return activity.getPackageManager().getPackageInfo("com.limelight",0)!=null;}
        catch(Exception ignored) {return false;}
    }
    boolean enabled() {return activity.getSharedPreferences("moonlight",0).getBoolean("enabled",true);}
    void setEnabled(boolean on) {
        activity.getSharedPreferences("moonlight",0).edit().putBoolean("enabled",on).apply();
        loadedStamp=Long.MIN_VALUE;
        if(!on) {removePanel();return;}
        if(resumed)resume();
    }

    boolean key(KeyEvent event) {
        if(panel==null || panel.getVisibility()!=View.VISIBLE || event.getAction()!=KeyEvent.ACTION_DOWN)return false;
        // Repeats of the held OK that started the move must not end it.
        if(moving!=null && (event.getRepeatCount()>0 || moveKey(event.getKeyCode())))return true;
        View focused=activity.getCurrentFocus();
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK && isInside(panel,focused)) {focusDock();return true;}
        if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP && isInside(grid,focused)) {
            RecyclerView.ViewHolder holder=grid.findContainingViewHolder(focused);
            if(holder!=null && holder.getBindingAdapterPosition()>=0 && holder.getBindingAdapterPosition()<((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount()) {
                focusGames();return true;
            }
        }
        View topButtons=activity.findViewById(R.id.topButtons);
        if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN && isInside(topButtons,focused)) {focusGames();return true;}
        return false;
    }
    private static boolean isInside(View parent,View child) {
        while(child!=null) {if(parent==child)return true;child=child.getParent() instanceof View?(View)child.getParent():null;}return false;
    }
    void dockFocused(int position,View card) {
        lastDock=card;lowerApps=position>=((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount();
        if(gameGlass!=null)gameGlass.setAlpha(0f);
        if(panel!=null){hero.setVisibility(View.INVISIBLE);position();}
    }
    private void focusDock() {
        if(lastDock!=null && lastDock.isAttachedToWindow())lastDock.requestFocusFromTouch();
        else if(grid.getChildCount()>0) {View card=grid.getChildAt(0).findViewById(R.id.card);if(card!=null)card.requestFocusFromTouch();}
    }
    private boolean hasGameCatalog() { return catalog!=null && catalog.hostUuid.equals(pickedUuid); }
    private void focusGames() {
        if(hasGameCatalog())cards.get(selected).requestFocusFromTouch();
        else if(fallbackCard!=null)fallbackCard.requestFocusFromTouch();
        else if(pcChoice!=null)pcChoice.requestFocusFromTouch();
    }
    private void showActiveGame(WolfSession session) {
        activeGameId=session!=null && session.active?session.appId:null;
        stopActivePulse();removePlayButton();
        if(catalog==null || fallback)return;
        if(moving==null)orderViews();
        int active=-1;
        if(activeGameId!=null)for(int i=0;i<cards.size();i++)if(activeGameId.equals(catalog.games.get(i).appId)){active=i;break;}
        for(int i=0;i<cards.size();i++) {
            TextView card=cards.get(i);
            View tile=(View)card.getParent();
            boolean on=i==active;
            tile.setForeground(on?shape(Color.TRANSPARENT,0xff8deaff):null);
            tile.setElevation(on?dp(12):0);
            card.setContentDescription(catalog.games.get(i).name+(on?" · Jogo em andamento, pressione OK para continuar":""));
            if(on)selected=i;
        }
        applyTileWidths();
        if(active>=0)decorateActive(cards.get(active));
    }

    /** Adds the pulsing glow and the centred play badge to the running game's tile. */
    private void decorateActive(TextView card) {
        View tile=(View)card.getParent();
        android.graphics.drawable.Drawable outline=tile.getForeground();
        if(animateGames && outline!=null) {
            activePulse=ObjectAnimator.ofInt(outline,"alpha",153,255);
            activePulse.setDuration(900);
            activePulse.setRepeatMode(ValueAnimator.REVERSE);
            activePulse.setRepeatCount(ValueAnimator.INFINITE);
            activePulse.start();
        }
        TextView play=text("▶",20,0xff171419);
        play.setGravity(Gravity.CENTER);
        GradientDrawable circle=new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xe6ffffff);
        play.setBackground(circle);
        play.setFocusable(false);
        play.setClickable(false);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((FrameLayout)tile).addView(play,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER));
        activePlay=play;
    }
    private void stopActivePulse() {if(activePulse!=null){activePulse.cancel();activePulse=null;}}
    private void removePlayButton() {
        if(activePlay!=null && activePlay.getParent() instanceof ViewGroup)((ViewGroup)activePlay.getParent()).removeView(activePlay);
        activePlay=null;
    }

    /** Applies the saved order (running game first) to the views in the row. */
    private void orderViews() {
        if(catalog==null || row==null || cards.isEmpty() || row.getChildCount()!=cards.size())return;
        List<View> views=new ArrayList<>();
        for(int index:MoonlightCatalog.displayOrder(catalog.games,savedOrder(),activeGameId)) {
            View tile=(View)cards.get(index).getParent();
            if(tile==null)return;
            views.add(tile);
        }
        arrangeRow(views);
    }

    /** Puts the row's tiles in this order without ever detaching the focused tile, so focus and the app grid stay put. */
    private void arrangeRow(List<View> views) {
        if(row==null || views.size()!=row.getChildCount())return;
        boolean same=true;
        for(int i=0;i<row.getChildCount();i++) {
            if(row.getChildAt(i)!=views.get(i)) {
                same=false;
                break;
            }
        }
        if(same)return;
        View focused=activity.getCurrentFocus();
        View keep=null;
        for(int i=0;i<row.getChildCount();i++) {
            if(isInside(row.getChildAt(i),focused)) {
                keep=row.getChildAt(i);
                break;
            }
        }
        // Detach every tile but the focused one, saving each tile's layout params.
        Map<View,ViewGroup.LayoutParams> params=new HashMap<>();
        for(int i=row.getChildCount()-1;i>=0;i--) {
            View child=row.getChildAt(i);
            params.put(child,child.getLayoutParams());
            if(child!=keep)row.removeViewAt(i);
        }
        for(int i=0;i<views.size();i++) {
            if(views.get(i)==keep)continue;
            row.addView(views.get(i),i,params.get(views.get(i)));
        }
        row.requestLayout();
    }
    private void applyTileWidths() {
        int base=tileWidth>0?tileWidth:dp(125);
        for(int i=0;i<cards.size();i++) {
            View tile=(View)cards.get(i).getParent();
            if(tile==null)continue;
            ViewGroup.LayoutParams lp=tile.getLayoutParams();
            if(lp==null)continue;
            int width=isActiveGame(i)?Math.round(base*1.3f):base;
            if(lp.width!=width) {lp.width=width;tile.setLayoutParams(lp);}
        }
    }
    private boolean isActiveGame(int index) {
        return activeGameId!=null && catalog!=null && index>=0 && index<catalog.games.size()
                && activeGameId.equals(catalog.games.get(index).appId);
    }
    private List<String> savedOrder() {
        List<String> names=new ArrayList<>();
        String raw=activity.getSharedPreferences("moonlight",0).getString("game_order","");
        if(raw==null || raw.isEmpty() || raw.length()>16384)return names;
        try {
            JSONArray saved=new JSONArray(raw);
            for(int i=0;i<saved.length() && i<MoonlightCatalog.MAX_GAMES*4;i++) {
                String name=saved.optString(i,null);
                if(name!=null)names.add(name);
            }
        } catch(org.json.JSONException ignored) {}
        return names;
    }
    private List<String> visualNames() {
        List<String> names=new ArrayList<>();
        if(row==null || catalog==null)return names;
        for(int i=0;i<row.getChildCount();i++) {
            View child=row.getChildAt(i);
            for(int game=0;game<cards.size();game++) {
                if(cards.get(game).getParent()==child) {names.add(catalog.games.get(game).name);break;}
            }
        }
        return names;
    }
    private void saveOrder() {
        JSONArray order=new JSONArray();
        for(String name:visualNames())order.put(name);
        activity.getSharedPreferences("moonlight",0).edit().putString("game_order",order.toString()).apply();
    }
    private void startMove(int index) {
        if(moving!=null || !hasGameCatalog() || index<0 || index>=cards.size())return;
        moving=cards.get(index);
        selected=index;
        if(animateGames) {
            for(int i=0;i<cards.size();i++) {
                View tile=(View)cards.get(i).getParent();
                if(tile==null)continue;
                ObjectAnimator wobble=ObjectAnimator.ofFloat(tile,"rotation",-1.5f,1.5f);
                wobble.setDuration(110+(i*10)%31);
                wobble.setRepeatMode(ValueAnimator.REVERSE);
                wobble.setRepeatCount(ValueAnimator.INFINITE);
                wobble.start();
                wobbles.add(wobble);
            }
        }
        for(int i=0;i<cards.size();i++) {
            View tile=(View)cards.get(i).getParent();
            if(tile!=null)tile.setAlpha(cards.get(i)==moving?1f:0.45f);
        }
    }
    private boolean moveKey(int key) {
        if(key==KeyEvent.KEYCODE_DPAD_LEFT) {movePicked(-1);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT) {movePicked(1);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER) {
            guardClick();
            endMove();
            return true;
        }
        if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN) {
            endMove();
            return true;
        }
        return false;
    }
    private void movePicked(int delta) {
        if(moving==null || row==null || catalog==null)return;
        List<String> names=visualNames();
        int from=row.indexOfChild((View)moving.getParent());
        int to=from+delta;
        if(from<0 || to<0 || to>=names.size())return;
        List<View> views=new ArrayList<>();
        for(String name:MoonlightCatalog.moved(names,from,to)) {
            for(int i=0;i<cards.size();i++) {
                if(catalog.games.get(i).name.equals(name)) {views.add((View)cards.get(i).getParent());break;}
            }
        }
        arrangeRow(views);
        moving.requestFocusFromTouch();
        if(scroll!=null)scroll.smoothScrollTo(Math.max(0,((View)moving.getParent()).getLeft()-dp(8)),0);
    }
    private void endMove() {
        if(moving==null)return;
        for(ObjectAnimator wobble:wobbles)wobble.cancel();
        wobbles.clear();
        for(TextView card:cards) {
            View tile=(View)card.getParent();
            if(tile!=null) {tile.setRotation(0f);tile.setAlpha(1f);}
        }
        moving=null;
        saveOrder();
    }
    /** A click queued by the OK key-up that leaves move mode must not open the game. */
    private void guardClick() {clickGuard=SystemClock.uptimeMillis()+350;}

    /** Minimal shelf when Moonlight is installed but no valid catalog is available. */
    private void showFallback() {
        fallback=true;catalog=null;
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);panel.setClipToPadding(false);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(220));
        pp.leftMargin=dp(60);pp.rightMargin=dp(60);pp.topMargin=dp(118);
        root.addView(panel,root.indexOfChild(glass),pp);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(36)));
        LinearLayout holder=new LinearLayout(activity);holder.setOrientation(LinearLayout.HORIZONTAL);holder.setClipChildren(false);
        FrameLayout tile=new FrameLayout(activity);tile.setClipChildren(false);
        View backdrop=new View(activity);backdrop.setBackground(shape(0xff242126,0));
        tile.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        TextView card=text("Abrir Moonlight",14,Color.WHITE);
        card.setGravity(Gravity.CENTER);card.setPadding(dp(8),dp(8),dp(8),dp(8));
        card.setBackground(shape(Color.TRANSPARENT,0));card.setFocusable(true);card.setClickable(true);
        card.setId(View.generateViewId());card.setContentDescription("Abrir Moonlight");
        tile.addView(card,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(125),-1);lp.rightMargin=dp(12);
        holder.addView(tile,lp);
        panel.addView(holder,new LinearLayout.LayoutParams(-1,0,1));
        fallbackCard=card;fallbackTile=tile;
        if(!lowerApps)parkDock();
        card.setOnClickListener(v->openMoonlight());
        card.setOnFocusChangeListener((v,focused)-> {
            card.setBackground(shape(Color.TRANSPARENT,focused?0xff8deaff:0));
            if(focused){glass.animate().cancel();glass.setAlpha(0f);}
        });
        card.setOnKeyListener((v,key,event)-> {
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusDock();return true;}
            return false;
        });
        hero.setVisibility(View.INVISIBLE);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff310500,Color.BLACK}));
        grid.requestLayout();
        position();
    }
    private void chooseHost() {
        int selectedHost=Math.max(0,hostUuids.indexOf(pickedUuid));
        hostDialog=new android.app.AlertDialog.Builder(activity).setTitle("PC para jogar")
                .setSingleChoiceItems(hostNames.toArray(new String[0]),selectedHost,(dialog,which)-> {
                    activity.getSharedPreferences("moonlight",0).edit().putString("host_uuid",hostUuids.get(which)).apply();
                    applyHost();dialog.dismiss();pcChoice.post(pcChoice::requestFocusFromTouch);
                }).setNeutralButton(hasGameCatalog()?"Jogo em andamento":"Gerenciar no Moonlight",(dialog,which)-> {if(hasGameCatalog() && sessionMenu!=null)sessionMenu.showCurrent();else openMoonlight();})
                .setNegativeButton("Cancelar",null).show();
    }
    private void applyHost() {
        String saved=activity.getSharedPreferences("moonlight",0).getString("host_uuid",catalog.hostUuid);
        int index=hostUuids.indexOf(saved);if(index<0)index=0;
        pickedName=hostNames.get(index);pickedUuid=hostUuids.get(index);
        pcChoice.setText("PC: "+pickedName+" ▾");
        scroll.setVisibility(hasGameCatalog()?View.VISIBLE:View.GONE);
        selectedName.setText(hasGameCatalog()?catalog.games.get(selected).name:"");
        library.setVisibility(hasGameCatalog()?View.GONE:View.VISIBLE);
        if(sessionMenu!=null){if(hasGameCatalog() && resumed)sessionMenu.resume();else sessionMenu.pause();}
    }
    private void launch(int index) {
        if(!hasGameCatalog()) {
            start(new Intent(Intent.ACTION_VIEW).setClassName("com.limelight","com.limelight.ShortcutTrampoline")
                    .putExtra("Name",pickedName).putExtra("UUID",pickedUuid));return;
        }
        MoonlightCatalog.Game game=catalog.games.get(index);
        if(game.appId==null) {
            // No app id: no session control, Moonlight's own lookup by name opens it.
            start(new Intent(Intent.ACTION_VIEW).setClassName("com.limelight","com.limelight.ShortcutTrampoline")
                    .putExtra("Name",catalog.hostName).putExtra("UUID",catalog.hostUuid).putExtra("AppName",game.name));
            return;
        }
        sessionMenu.open(game.appId,game.name);
    }
    private void launchControlled(String id,String name) {
        if(!resumed || !hasGameCatalog())return;
        returningFromGame=true;lowerApps=false;
        for(int i=0;i<catalog.games.size();i++)if(id.equals(catalog.games.get(i).appId))selected=i;
        activity.getSharedPreferences("moonlight",0).edit().putBoolean("return_to_games",true).putString("game_name",catalog.games.get(selected).name).apply();
        start(new Intent(Intent.ACTION_VIEW).setClassName("com.limelight","com.limelight.ShortcutTrampoline")
                .putExtra("Name",catalog.hostName).putExtra("UUID",catalog.hostUuid).putExtra("AppName",name).putExtra("AppId",id));
    }
    void pause() {resumed=false;if(sessionMenu!=null)sessionMenu.pause();}
    private void openMoonlight() {start(new Intent(Intent.ACTION_MAIN).setClassName("com.limelight","com.limelight.PcView"));}
    private void start(Intent intent) {
        try {activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));}catch(ActivityNotFoundException|SecurityException e) {Toast.makeText(activity,"Não foi possível abrir o Moonlight. Confira se ele está instalado.",Toast.LENGTH_LONG).show();}
    }
    private void removePanel() {
        if(sessionMenu!=null)sessionMenu.close();sessionMenu=null;
        if(gameSpringX!=null)gameSpringX.cancel();if(gameSpringY!=null)gameSpringY.cancel();
        if(gameGlass!=null)root.removeView(gameGlass);gameGlass=null;
        for(ObjectAnimator wobble:wobbles)wobble.cancel();
        wobbles.clear();
        stopActivePulse();removePlayButton();
        if(panel!=null)root.removeView(panel);
        panel=null;row=null;catalog=null;cards.clear();covers.clear();recycle(bitmaps);bitmaps.clear();panelHeight=0;
        fallback=false;fallbackCard=null;fallbackTile=null;moving=null;activeGameId=null;tileWidth=0;
        root.setBackground(originalBackground);
        hero.setVisibility(Config.load(activity).dockTitle?View.VISIBLE:View.GONE);
        grid.requestLayout();
    }
    private static void recycle(List<Bitmap> images) {for(Bitmap b:images)if(b!=null && !b.isRecycled())b.recycle();}
    void close() {
        destroyed=true;if(hostDialog!=null)hostDialog.dismiss();loader.shutdownNow();root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);removePanel();
    }
}

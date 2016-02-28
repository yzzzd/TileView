package com.qozix.tileview.tiles;

import android.os.Process;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TileRenderPoolExecutor extends ThreadPoolExecutor {

  private static final int KEEP_ALIVE_TIME = 1;
  private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

  private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
  private static final int MAXIMUM_POOL_SIZE = Runtime.getRuntime().availableProcessors();

  private WeakReference<TileCanvasViewGroup> mTileCanvasViewGroupWeakReference;

  private List<TileRenderTask> mExecutingTileRenderTasks;
  private List<TileRenderTask> mCancelledTileRenderTasks;

  public TileRenderPoolExecutor() {
    super(
      CORE_POOL_SIZE,
      MAXIMUM_POOL_SIZE,
      KEEP_ALIVE_TIME,
      KEEP_ALIVE_TIME_UNIT,
      new LinkedBlockingDeque<Runnable>()
    );
    mExecutingTileRenderTasks = (List) Collections.synchronizedList( new LinkedList<>() );
    mCancelledTileRenderTasks = (List) Collections.synchronizedList( new LinkedList<>() );
  }

  public void cancel() {
    if( getQueue().size() > 0 || getActiveCount() > 0 ) {
      Log.d( "DEBUG", "queue 0, active count 0, dispatch render cancelled event" );
      TileCanvasViewGroup tileCanvasViewGroup = mTileCanvasViewGroupWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.onRenderTaskCancelled();
        Log.d( "DEBUG", "render cancelled event dispatched" );
      }
    }
    //getQueue().clear();
    mCancelledTileRenderTasks.addAll( mExecutingTileRenderTasks );
    mExecutingTileRenderTasks.clear();
    stopCancelledTasks();
  }

  @Override
  public void shutdown() {
    cancel();
    super.shutdown();
  }

  @Override
  @NonNull
  public List<Runnable> shutdownNow() {
    cancel();
    return super.shutdownNow();
  }

  private void stopCancelledTasks() {
    for( TileRenderTask tileRenderTask : mCancelledTileRenderTasks ) {
      tileRenderTask.cancel();
    }
    mCancelledTileRenderTasks.clear();
  }

  public void queue( TileCanvasViewGroup tileCanvasViewGroup, List<Tile> renderList ) {
    synchronized( this ) {
      Log.d( "DEBUG", "TileRenderPoolExecutor.queue, before intersection: " + mExecutingTileRenderTasks.size() + ", " + mCancelledTileRenderTasks.size() );
      List<TileRenderTask> completeTasks = new LinkedList<>();
      for( TileRenderTask tileRenderTask : mExecutingTileRenderTasks ) {
        if( tileRenderTask.runnable.isDone()){
          completeTasks.add( tileRenderTask );
        } else {
          if( renderList.contains( tileRenderTask.tile ) ) {
            renderList.remove( tileRenderTask.tile );
          } else {
            mCancelledTileRenderTasks.add( tileRenderTask );
          }
        }
      }
      mExecutingTileRenderTasks.removeAll( completeTasks );
      mExecutingTileRenderTasks.removeAll( mCancelledTileRenderTasks );
      Log.d( "DEBUG", "TileRenderPoolExecutor.queue, after intersection: " + mExecutingTileRenderTasks.size() + ", " + mCancelledTileRenderTasks.size() );
      stopCancelledTasks();
      if( renderList.size() > 0 ) {
        mTileCanvasViewGroupWeakReference = new WeakReference<>( tileCanvasViewGroup );
        tileCanvasViewGroup.onRenderTaskPreExecute();
        for( Tile tile : renderList ) {
          if( isShutdownOrTerminating() ) {
            return;
          }
          TileRenderTask task = new TileRenderTask();
          task.tile = tile;
          task.runnable = new TileRenderRunnable();
          task.runnable.setTile( tile );
          task.runnable.setTileCanvasViewGroup( tileCanvasViewGroup );
          task.runnable.setTileRenderPoolExecutor( this );
          task.future = submit( task.runnable );
          mExecutingTileRenderTasks.add( task );
        }
      }
    }
  }

  private TileRenderTask getTaskByTile( Tile tile ) {
    if( tile != null ) {
      for( TileRenderTask tileRenderTask : mExecutingTileRenderTasks ) {
        if( tile.equals( tileRenderTask.tile ) ) {
          return tileRenderTask;
        }
      }
    }
    return null;
  }

  public void removeTaskFromCurrentlyExecutingList( TileRenderTask tileRenderTask ) {
    synchronized( this ) {
      Log.d( "DEBUG", "should be removing task: " + mExecutingTileRenderTasks.size() );
      mExecutingTileRenderTasks.remove( tileRenderTask );
      Log.d( "DEBUG", "task should have been removed: " + mExecutingTileRenderTasks.size() );
    }
  }

  public boolean isShutdownOrTerminating() {
    return isShutdown() || isTerminating() || isTerminated();
  }

  @Override
  protected void afterExecute( Runnable runnable, Throwable throwable ) {
    synchronized( this ) {
      Log.d( "DEBUG", "afterExecute" );
      super.afterExecute( runnable, throwable );
      if( getQueue().size() == 0 && getActiveCount() == 1 ) {
        mExecutingTileRenderTasks.clear();
        TileCanvasViewGroup tileCanvasViewGroup = mTileCanvasViewGroupWeakReference.get();
        if( tileCanvasViewGroup != null ) {
          tileCanvasViewGroup.onRenderTaskPostExecute();
          Log.d( "DEBUG", "afterExecute should send onRenderTaskPostExecute" );
          Log.d( "DEBUG", "executing tasks: " + mExecutingTileRenderTasks.size() );
        }
      }
    }
  }

  private static class TileRenderTask {

    public Future future;
    public TileRenderRunnable runnable;
    public Tile tile;

    public void cancel() {
      future.cancel( true );
      runnable.cancel();
      tile.destroy( true );
    }
  }

  private static class TileRenderRunnable implements Runnable {

    private WeakReference<TileRenderPoolExecutor> mTileRenderPoolExecutorWeakReference;
    private WeakReference<TileCanvasViewGroup> mTileCanvasViewGroupWeakReference;
    private WeakReference<Tile> mTileWeakReference;

    private volatile boolean mCancelled = false;
    private volatile boolean mComplete = false;

    public TileRenderRunnable() {

    }

    public void cancel() {
      mCancelled = true;
    }

    public void markComplete() {
      mComplete = true;
      /*
      TileRenderPoolExecutor tileRenderPoolExecutor = getTileRenderPoolExecutor();
      if( tileRenderPoolExecutor != null ) {
        Tile tile = getTile();
        if( tile != null ) {
          TileRenderTask tileRenderTask = tileRenderPoolExecutor.getTaskByTile( tile );
          tileRenderPoolExecutor.removeTaskFromCurrentlyExecutingList( tileRenderTask );
        }
      }
      */
    }

    public boolean isComplete(){
      return mComplete;
    }

    public boolean isDone(){
      return mCancelled || mComplete;
    }

    public void setTileRenderPoolExecutor( TileRenderPoolExecutor tileRenderPoolExecutor ) {
      mTileRenderPoolExecutorWeakReference = new WeakReference<>( tileRenderPoolExecutor );
    }

    public TileRenderPoolExecutor getTileRenderPoolExecutor() {
      if( mTileRenderPoolExecutorWeakReference != null ) {
        return mTileRenderPoolExecutorWeakReference.get();
      }
      return null;
    }

    public void setTileCanvasViewGroup( TileCanvasViewGroup tileCanvasViewGroup ) {
      mTileCanvasViewGroupWeakReference = new WeakReference<>( tileCanvasViewGroup );
    }

    public TileCanvasViewGroup getTileCanvasViewGroup() {
      if( mTileCanvasViewGroupWeakReference != null ) {
        return mTileCanvasViewGroupWeakReference.get();
      }
      return null;
    }

    public void setTile( Tile tile ) {
      mTileWeakReference = new WeakReference<>( tile );
    }

    public Tile getTile() {
      if( mTileWeakReference != null ) {
        return mTileWeakReference.get();
      }
      return null;
    }

    public boolean renderTile() {
      if( mCancelled ) {
        return false;
      }
      Process.setThreadPriority( Process.THREAD_PRIORITY_BACKGROUND );
      final Thread thread = Thread.currentThread();
      if( thread.isInterrupted() ) {
        return false;
      }
      TileCanvasViewGroup tileCanvasViewGroup = getTileCanvasViewGroup();
      if( tileCanvasViewGroup == null ) {
        return false;
      }
      if( tileCanvasViewGroup.getRenderIsCancelled() ) {
        return false;
      }
      Tile tile = getTile();
      if( tile == null ) {
        return false;
      }
      try {
        tileCanvasViewGroup.generateTileBitmap( tile );
      } catch( InterruptedIOException e ) {
        Thread.currentThread().interrupt();
        return false;
      } catch( Exception e ) {
        return false;
      }
      if( mCancelled || tile.getBitmap() == null || thread.isInterrupted() || tileCanvasViewGroup.getRenderIsCancelled() ) {
        tile.destroy( true );
        return false;
      }
      tileCanvasViewGroup.addTileToCurrentTileCanvasView( tile );
      return true;
    }

    @Override
    public void run() {
      renderTile();
      markComplete();
    }
  }
}

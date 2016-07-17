package com.ath.fuel;

import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.view.View;

import com.ath.fuel.err.FuelInjectionException;
import com.ath.fuel.err.FuelScopeViolationException;
import com.ath.fuel.err.FuelUnableToObtainContextException;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class FuelInjector {
	private static FuelInjector injector = new FuelInjector();
	private static Application app;
	private static Activity activity; // Not a weakref -- this is okay because it gets assigned to the next activity each time a new activity is
	// created, so the previous
	// one is never held unless the app goes to the background in which case, if the activity is onDestroy'd there will have to be a
	// new one when the app is resumed so still calls onActivityCreate so okay! -- We chose not to use a weakref because in the rare
	// case of low memory, it could get GC'd and then you end up referring to a null activity I think its not bad to hold onto in this
	// case

	private static boolean isDebug = false;
	private static ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
	private static final WeakHashMap<Object, Lazy> lazyCache = new WeakHashMap<>(); // parent -> LazyParent
	private static WeakHashMap<Lazy, Queue<Lazy>> preprocessQueue = new WeakHashMap<>(); // LazyParent -> Queue<LazyChildren>
	private static WeakHashMap<Lazy, WeakReference<Lazy>> weakLazyParentCache = new WeakHashMap<>(); // LazyParent -> WeakLazyParent

	// private static WeakHashMap<Object, WeakReference<Context>> objectToContext = new WeakHashMap<>();

	/**
	 * True will tighten up tolerances for quicker failures and more verbosity
	 */
	public static final boolean isDebug() {
		return isDebug;
	}

	/**
	 * True will tighten up tolerances for quicker failures and more verbosity
	 */
	public static final void setDebug( boolean debug ) {
		isDebug = debug;
	}

	/**
	 * @return null if the FuelInjector has not yet been {@link #isInitialized()}
	 */
	public static final @Nullable Application getApp() {
		return app;
	}

	/**
	 * not guaranteed to exist
	 */
	static final @Nullable Activity getActivity() {
		return activity;
	}

	static WeakReference<Context> getContextRef( Context context ) {
		synchronized ( context ) { // synchronized on context because we dont want to hold up an activity when a background service is working
			context = toContext( context );
			WeakReference<Context> out = injector.contextToWeakContextCache.get( context );
			if ( out == null ) {
				out = new WeakReference<Context>( context );
				injector.contextToWeakContextCache.put( context, out );
			}
			return out;
		}
	}

	/**
	 * Get the real context when the given context is a wrapper
	 *
	 * @param context - ambiguous context
	 */
	static final Context toContext( Context context ) {
		if ( context != null ) {
			if ( context instanceof Activity ) {
				// no-op
			} else if ( context instanceof Application ) {
				// no-op
			} else if ( context instanceof Service ) {
				// no-op
			} else if ( context instanceof ContextWrapper ) { // some other foreign context
				Context out = toContext( ( (ContextWrapper) context ).getBaseContext() );
				if ( out != null ) {
					context = out;
				}
			}
		}
		return context;
	}

	/**
	 * @return True after {@link #initializeModule(FuelModule)}
	 */
	public static boolean isInitialized() {
		if ( app == null || injector.fuelModule == null ) {
			return false;
		}
		return true;
	}

	/**
	 * @return true if this class is truly a Context
	 */
	static boolean isContext( Class<?> clazz ) {
		return Context.class.isAssignableFrom( clazz );
	}

	/**
	 * @see #isInitialized()
	 */
	public static boolean isUninitialized() {
		return !isInitialized();
	}

	static <T> Class<? extends T> toLeafType( Class<T> type, Integer flavor ) {
		return injector.fuelModule.getType( type, flavor );
	}

	static boolean isAppSingleton( Class<?> leafType ) {
		return leafType.isAnnotationPresent( AppSingleton.class );
	}

	static boolean isActivitySingleton( Class<?> leafType ) {
		return leafType.isAnnotationPresent( ActivitySingleton.class );
	}

	static boolean isSingleton( Class<?> leafType ) {
		return isAppSingleton( leafType ) || isActivitySingleton( leafType );
	}

//	static Context findContext( Object instance ) {
//		synchronized ( objectToContext ) {
//			if ( instance == null ) {
//				return null;
//			}
//			WeakReference<Context> ref = objectToContext.get( instance );
//			if ( ref != null ) {
//				return ref.get();
//			}
//			return null;
//		}
//	}
//
//	static void rememberContext( Object instance, Context context ) {
//		synchronized ( objectToContext ) {
//			if ( findContext( instance ) == null ) {
//				objectToContext.put( instance, new WeakReference<Context>( context ) );
//			}
//		}
//	}

	static Lazy findLazyByInstance( Object instance ) {
		return lazyCache.get( instance );
	}

	static void rememberLazyByInstance( Object instance, Lazy lazy ) {
		lazyCache.put( instance, lazy );
	}


//	private static void checkObjectCacheSize() {
//		try {
//			int size = objectToContext.keySet().size();
//			if ( ( size >= 500 ) && ( ( size % 100 ) == 0 ) ) {
//				FLog.w( "FUEL: FindLazy: Size: %s", size );
//				Set<Object> objs = objectToContext.keySet();
//				final ConcurrentMap<String, AtomicInteger> classCountMap = new ConcurrentHashMap<String, AtomicInteger>();
//				for ( Object o : objs ) {
//					String name = o.getClass().getSimpleName();
//					classCountMap.putIfAbsent( name, new AtomicInteger() );
//					classCountMap.get( name ).incrementAndGet();
//				}
//				List<String> classNameList = new ArrayList<String>( classCountMap.keySet() );
//				Collections.sort( classNameList, new Comparator<String>() {
//					@Override
//					public int compare( String lhs, String rhs ) {
//						int lhsCount = classCountMap.get( lhs ).get();
//						int rhsCount = classCountMap.get( rhs ).get();
//						return rhsCount - lhsCount;
//					}
//				} );
//				int totalSingleObjectInstances = 0;
//				for ( String name : classNameList ) {
//					if ( classCountMap.get( name ).get() == 1 ) {
//						totalSingleObjectInstances++;
//					} else {
//						FLog.w( "-- FUEL: FindLazy: name=%-40s count=%s", name, classCountMap.get( name ) );
//					}
//				}
//				FLog.w( "-- FUEL: FindLazy: %-45s count=%s", "Objects with one reference", totalSingleObjectInstances );
//			}
//		} catch ( Exception e ) {
//			FLog.e( e );
//		}
//	}


	// static void enqueuePreToPostProcessingMerge( Lazy lazy, Object parent ) {
	// synchronized ( lazy.type ) {
	// // FLog.d( "ENQUEUE: %s childof %s", lazy.type.getSimpleName(), parent.getClass().getSimpleName() );
	//
	// // preprocessQueue.add( lazy );
	// putPreprocessQueue( parent, lazy );
	// }
	// }

	private WeakHashMap<Object, List<Exception>> igniteCount;


	static WeakReference<Lazy> attainWeakLazy( Lazy lazy ) {
		WeakReference<Lazy> out = weakLazyParentCache.get( lazy );
		if ( out == null ) {
			out = new WeakReference<Lazy>( lazy );
			weakLazyParentCache.put( lazy, out );
		}
		return out;
	}

	/**
	 * Associates a context to the given instance so that its injections may find it in the dependency hierarchy.<br>
	 * Also dequeues any injections that were queued up due to a context not being known at the time of Lazy.attain.<br>
	 * <br>
	 * NOTE:<br>
	 * You may skip {@link #ignite(Context, Object)} when a the object has is mapped to a context via {@link FuelModule#provideContext(Object)}<br>
	 * One exception to this rule is that injections are always queued up until Fuel has been initialized.<br>
	 */
	public static void ignite( Context context, Object instance ) throws FuelUnableToObtainContextException, FuelScopeViolationException {
		if ( instance == null ) {
			return;
		}

		// Bail out if in edit mode
		if ( instance instanceof View ) {
			if ( ( (View) instance ).isInEditMode() ) {
				return;
			}
		}

		// skip wrappers
		context = toContext( context );


		// In the case of a service, we need to plug it into the cache after it calls ignite because we cant construct it
		if ( instance instanceof Service ) {
			CacheKey key = CacheKey.attain( instance.getClass() );
			injector.putObjectByContextType( context, key, instance );
		}

		// whatever this is we're igniting, it wasn't injected so lets artificially create a lazy for it
		// so that its children can find their parent
		//rememberContext( instance, context );
		Lazy parent = findLazyByInstance( instance );
		if ( parent == null ) {
			parent = Lazy.newEmptyParent( instance );
			rememberLazyByInstance( instance, parent );
		}

		if ( !parent.isInitialized() ) {
			doPreProcessParent( parent, context );
		}

		// Now that this instance has been ignited, it is okay to dequeue its queued up injections
		dequeuePreProcesses( parent );

		if ( instance instanceof OnFueled ) {
			try {
				( (OnFueled) instance ).onFueled();
			} catch ( Exception e ) {
				FLog.e( e );
			}
		}
	}

	private static Collection<Lazy> getPreprocessQueue( final Lazy parent, boolean readonly ) {
		synchronized ( parent ) {
			Queue<Lazy> queue = preprocessQueue.get( parent );
			if ( queue != null ) {
				return queue;
			} else if ( !readonly ) {
				queue = new LinkedList<Lazy>();
				preprocessQueue.put( parent, queue );
				return queue;
			}
			return Collections.emptyList();
		}
	}

	static void enqueueLazy( Lazy parent, Lazy lazy ) {
		synchronized ( parent ) {
			Collection<Lazy> queue = getPreprocessQueue( parent, false );
			queue.add( lazy );
		}
	}

	static void dequeuePreProcesses( final Lazy parent ) throws FuelUnableToObtainContextException, FuelScopeViolationException {
		synchronized ( parent ) {
			Collection<Lazy> queue = getPreprocessQueue( parent, true );
			for ( Lazy lazy : queue ) {
				doPreProcess( lazy, parent );
			}
			queue.clear();
		}
	}

	static void doServicePreProcess( Lazy lazy, Context context ) {

		if ( lazy.isDebug() ) {
			FLog.leaveBreadCrumb( "doServicePreProcess for %s, context is %s", lazy, context == null ? "null" : context.getClass().getSimpleName() );
		}

		// in the special case of a service, we need to spawn it now so that its ready when we call get
		// FLog.d( "SERVICE: doServicePreProcess Service: %s", lazy.leafType.getSimpleName() );

		if ( Service.class.isAssignableFrom( lazy.leafType ) ) {
			CacheKey key = CacheKey.attain( lazy.leafType );
			// FLog.d( "SERVICE: doServicePreProcess get Service: %s", lazy.leafType.getSimpleName() );
			Object service = getServiceInstance( context, key, false );
			if ( service != null ) {
				// FLog.d( "SERVICE: doServicePreProcess got Service: %s = %s", lazy.leafType.getSimpleName(), service );
				lazy.instance = service;
			} else {
				// FLog.d( "SERVICE: Starting Service: %s", lazy.leafType.getSimpleName() );
				FuelInjector.getApp().startService( new Intent( FuelInjector.getApp(), lazy.leafType ) );
			}
		}
	}

	static void doPreProcessParent( Lazy parent, Context context ) {
		parent.setContext( context, false );
		parent.scope = determineScope( parent.leafType );
		Scope contextScope = determineScope( context.getClass() );
		validateScope( contextScope, parent.scope );
		parent.setLeafType( FuelInjector.toLeafType( parent.type, parent.getFlavor() ) );
		parent.parentNode = null;
	}

	/**
	 * Process child prior to child-lazy's knowledge of it's instance
	 * We initialize the child-lazy now that we know the context.
	 * We call this PreProcess because its before the parent has called child-lazy.get()
	 * though don't be confused, this is AFTER the lazy has been de-queued.
	 */
	static void doPreProcess( Lazy child, Lazy parent ) throws FuelUnableToObtainContextException, FuelScopeViolationException {
		Context context = parent.getContext();
		if ( child.isDebug() ) {
			FLog.leaveBreadCrumb( "doPreProcess for %s, context is %s", child, context == null ? "null" : context.getClass().getSimpleName() );
		}

		Context lazyContext = context;

		child.scope = determineScope( child.leafType );
		validateScope( parent.scope, child.scope );
		child.parentNode = attainWeakLazy( parent );

		child.setLeafType( FuelInjector.toLeafType( child.type, child.getFlavor() ) );


		if ( isAppSingleton( child.leafType ) ) {
			lazyContext = getApp();
			if ( child.isDebug() ) {
				FLog.leaveBreadCrumb( "doPreProcess for app singleton %s, so context is %s", child,
						context == null ? "null" : context.getClass().getSimpleName() );
			}
		}

		child.setContext( lazyContext, false );

		if ( child.isDebug() ) {
			FLog.leaveBreadCrumb( "doPreProcess for %s, context ended up with %s", child,
					context == null ? "null" : context.getClass().getSimpleName() );
		}

		if ( Service.class.isAssignableFrom( child.leafType ) ) {
			doServicePreProcess( child, lazyContext );
		}
	}

	static Scope determineScope( Class leafType ) {
		if ( leafType != null ) {
			if ( isAppSingleton( leafType ) ) {
				return Scope.Application;
			} else if ( Application.class.isAssignableFrom( leafType ) ) {
				return Scope.Application;
			} else if ( isActivitySingleton( leafType ) ) {
				return Scope.Activity;
			} else if ( Activity.class.isAssignableFrom( leafType ) ) {
				return Scope.Activity;
			} else if ( Context.class.isAssignableFrom( leafType ) ) {
				return Scope.Application;
			}
		}
		return Scope.Undef;
	}

	static void validateScope( Scope parent, Scope child ) throws FuelScopeViolationException {
		if ( parent == null || child == null || !parent.canAccess( child ) ) {
			throw new FuelScopeViolationException( "Fuel Scope Violation: %s cannot access %s", parent, child );
		}
	}

	/**
	 * after we have an instance
	 */
	static void doPostProcess( Lazy lazy ) throws FuelUnableToObtainContextException, FuelScopeViolationException {
		rememberLazyByInstance( lazy.getInstance(), lazy );
		dequeuePreProcesses( lazy );
	}

	/**
	 * Associate the given {@link FuelModule} to the {@link FuelInjector}<br>
	 * The {@link FuelInjector} will behave based on the {@link FuelModule} overrides allowing
	 * for detailed customization or alternate bindings between Test, Debug and Release flavored modules.<br>
	 * <br>
	 * Required to be called before any injections are expected to work.<br>
	 * Best to call from App.onCreate()
	 */
	@MainThread
	public static final void initializeModule( FuelModule fuelModule ) throws FuelUnableToObtainContextException, FuelScopeViolationException {
		if ( FuelInjector.app != null ) {
			FLog.w( "initializeModules called again -- be careful!" );

			// We do this to support resetting the the fuel module for testing
			// anything that was registered can now unregister itself before death
			// its not pretty but it works
			if ( injector.fuelModule != null ) {
				injector.fuelModule.prepareForDeath();
			}
		}

		injector.mainThreadId = Thread.currentThread().getId();
		FuelInjector.app = fuelModule.getApplication();

		fuelModule.setActivityCallbacks( new ActivityLifecycleCallbacks() {
			@Override
			public void onActivityCreated( Activity activity, Bundle savedInstanceState ) {
				try {
					FuelInjector.activity = activity;
					ignite( activity, activity );
				} catch ( Exception e ) {
					FLog.e( e );
				}
			}

			@Override
			public void onActivityStarted( Activity activity ) {
				FuelInjector.activity = activity;
			}

			@Override
			public void onActivityResumed( Activity activity ) {
				FuelInjector.activity = activity;
			}

			@Override
			public void onActivityPaused( Activity activity ) {
			}

			@Override
			public void onActivityStopped( Activity activity ) {
			}

			@Override
			public void onActivitySaveInstanceState( Activity activity, Bundle outState ) {
			}

			@Override
			public void onActivityDestroyed( Activity activity ) {
			}
		} );

		injector.fuelModule = fuelModule;
		fuelModule.configure();

		ignite( app, app );
	}

	// TODO: remove?
	private static final void executeFirstMethodMatchingThisAnnotation( Collection<Object> objects, Class<? extends Annotation> annotation ) {
		FLog.d( "onFuel: executeFirstMethod begin" );
		for ( Object o : objects ) {
			try {
				Method method = null;
				for ( Method m : o.getClass().getDeclaredMethods() ) {
					if ( m.isAnnotationPresent( annotation ) ) {
						method = m;
						break;
					}
				}

				if ( method != null ) {
					if ( o != null ) {
						method.setAccessible( true );
						method.invoke( o, (Object[]) null );
					}
				}
			} catch ( Exception e ) {
				FLog.e( e );
			}
		}
		FLog.d( "onFuel: executeFirstMethod end" );
	}

	static final <T> T attain( Context context, Class<T> type ) {
		return attain( context, type, CacheKey.DEFAULT_FLAVOR );
	}

	static final <T> T attain( Context context, Class<T> type, Integer flavor ) {
		try {
			Lazy<T> lazy = Lazy.attain( context, type, flavor );
			return attainInstance( CacheKey.attain( lazy ), lazy, true );
		} catch ( Exception e ) {
			FLog.e( "Unable to attain instance of %s", type );
			throw new IllegalStateException( e );
		}
	}

	/**
	 * Does not attain, only returns an item that is already in the cache or null.
	 */
	@Nullable public static final <T> T findInstance( Context context, Class<T> type ) {
		return getInstance( context, CacheKey.attain( type ), false );
	}

	/**
	 * Does not attain, only returns an item that is already in the cache or null.
	 */
	@Nullable public static final <T> T findInstance( Context context, Class<T> type, Integer flavor ) {
		return getInstance( context, CacheKey.attain( type, flavor ), false );
	}

	static final <T> T getInstance( Context context, CacheKey key, boolean debug ) {
		// if ( Application.class.isAssignableFrom( key.getLeafType() ) ) {
		// return (T) getApp();
		// } else if ( Activity.class.isAssignableFrom( key.getLeafType() ) ) {
		//
		// // FIXME: FUEL this breaks when injecting an Activity from a Module using the App context :(
		// if ( context instanceof Activity ) {
		// return (T) context;
		// } else {
		// // throw new IllegalStateException( "You're trying to create a context singleton with an app context -- not cool" );
		// FLog.w( "You are injecting an Activity from an Application Context, this will result in the 'Active' Activity." );
		// return (T) getActivity();
		// }
		// } else if ( Activity.class.isAssignableFrom( key.getLeafType() ) || Application.class.isAssignableFrom( key.getLeafType() ) ) {
		// return (T) context;
		// }

		// a service wants a context
		// -- context = service or app
		// -- leaf = Context, App or Activity

		// an activity wants a service
		// -- context = activity or app
		// -- leaf = service

		if ( Application.class.isAssignableFrom( key.getLeafType() ) ) {
			if ( debug ) {
				FLog.leaveBreadCrumb( "getInstance for App got %s", getApp() == null ? "null" : getApp().getClass().getSimpleName() );
			}
			return (T) getApp();
		} else if ( Activity.class.isAssignableFrom( key.getLeafType() ) && context instanceof Activity ) {
			if ( debug ) {
				FLog.leaveBreadCrumb( "getInstance for Activity got %s", context == null ? "null" : context.getClass().getSimpleName() );
			}
			return (T) context;
		} else if ( Service.class.isAssignableFrom( key.getLeafType() ) ) {
			final T serviceInstance = getServiceInstance( context, key, true );
			if ( debug ) {
				FLog.leaveBreadCrumb( "getInstance for Service got %s",
						serviceInstance == null ? "null" : serviceInstance.getClass().getSimpleName() );
			}
			return serviceInstance;
		} else if ( Context.class.isAssignableFrom( key.getLeafType() ) ) {
			if ( debug ) {
				FLog.leaveBreadCrumb( "getInstance for Context got %s", context == null ? "null" : context.getClass().getSimpleName() );
			}
			return (T) context;
		}

		final T objectByContextType = (T) injector.getObjectByContextType( context, key );
		if ( debug ) {
			FLog.leaveBreadCrumb( "getInstance getObjectByContextType got %s", objectByContextType == null ? "null" : objectByContextType.getClass()
					.getSimpleName() );
		}

		return objectByContextType;
	}

	// FIXME: need an attainService and getService
	// Currently ignite is talking directly to the cache and is messy :(
	private static final <T> T getServiceInstance( Context context, CacheKey key, boolean willingToWait ) {
		boolean inMainThread = inMainThread();
		long maxTimeMillis = 1000;
		long sleepTimeMillis = 20;
		long startTime = System.currentTimeMillis();
		int loopCount = 0;
		long duration = 0;
		do {
			try {
				++loopCount;

				T object = (T) injector.getObjectByContextType( getApp(), key );
				if ( object != null ) {
					// FLog.d( "SERVICE: getInstance[%s][%s] of %s got %s", loopCount, duration, key.getLeafType().getSimpleName(), object );
					return object;
				}

				if ( !inMainThread && willingToWait ) {
					duration = System.currentTimeMillis() - startTime;
					if ( duration <= ( maxTimeMillis - sleepTimeMillis ) ) {
						try {
							Thread.sleep( sleepTimeMillis );
						} catch ( InterruptedException e ) {
							FLog.e( e );
						}
					} else {
						// FLog.d( "SERVICE: getInstance[%s][%s] of %s got %s", loopCount, duration, key.getLeafType().getSimpleName(), null );
						return null;
					}
				} else {
					// FIXME: FUEL most likely a lazy attain but i'm not comfortable with this yet...
					// lets test calling lazyService.get() in the main thread -- i hope it will just do everything synchronously and end up with a
					// service
					// but if not, consider allowing the polling to run for some small amount of time on the main thread?
					// FLog.d( "SERVICE: getInstance (Main Thread) of %s got %s", key.getLeafType().getSimpleName(), null );
					return null;
				}
			} catch ( Exception e ) {
				FLog.e( e );
				return null;
			}
		} while ( true );
	}

	// safe because we hash by type
	@SuppressWarnings( "unchecked" )
	static final <T> T newInstance( CacheKey key, Lazy lazy, boolean allowAnonymousNewInstance ) throws FuelInjectionException {
		try {
			T object = null;
			if ( isSingleton( lazy.leafType ) ) {
				if ( lazy.isDebug() ) {
					FLog.leaveBreadCrumb( "newInstance for singleton %s", lazy );
				}
				synchronized ( lazy.leafType ) { // FIXME: concurrency fail since classes are not guaranteed to be the same instance
					object = (T) injector.getObjectByContextType( lazy.getContext(), key );
					if ( lazy.isDebug() ) {
						FLog.leaveBreadCrumb( "newInstance getObjectByContextType returned %s for %s",
								object == null ? "null" : object.getClass().getSimpleName(),
								lazy );
					}
					if ( object == null ) { // safety check in case another thread did the work while we were waiting
						object = (T) injector.fuelModule.obtainInstance( lazy, allowAnonymousNewInstance );
						if ( lazy.isDebug() ) {
							FLog.leaveBreadCrumb( "newInstance obtainInstance returned %s for %s",
									object == null ? "null" : object.getClass().getSimpleName(),
									lazy );
						}
						if ( object != null ) {
							injector.putObjectByContextType( lazy.getContext(), key, object );
						}
					}
				}
			} else {
				if ( lazy.isDebug() ) {
					FLog.leaveBreadCrumb( "newInstance for non-singleton leaf: %s, type: %s",
							lazy.leafType == null ? "null" : lazy.leafType.getSimpleName(),
							lazy );
				}
				object = (T) injector.fuelModule.obtainInstance( lazy, allowAnonymousNewInstance );
			}
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "newInstance returning %s for leaf type of lazy: %s",
						object == null ? "null" : object.getClass().getSimpleName(), lazy );
			}
			return object;
		} catch ( FuelInjectionException e ) {
			throw e;
		} catch ( Exception e ) {
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "newInstance Exception %s", e.getMessage() );
			}
			throw new FuelInjectionException( e );
		}
	}

	static final <T> T attainInstance( CacheKey key, Lazy<T> lazy, boolean allowAnonymousNewInstance ) throws FuelInjectionException {

		try {
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "attainInstance for key: %s and lazy: %s", key, lazy );
			}

			T obj = getInstance( lazy.getContext(), key, lazy.isDebug() ); // go into getInstance here. grrrr.
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "attainInstance getInstance returned %s", obj == null ? "null" : obj.getClass().getSimpleName() );
			}

			if ( obj == null ) {
				obj = newInstance( key, lazy, allowAnonymousNewInstance );
			}
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "attainInstance ended up with %s", obj == null ? "null" : obj.getClass().getSimpleName() );
			}
			return obj;
		} catch ( FuelInjectionException e ) {
			throw FuelInjector.doFailure( lazy, e );
		} catch ( Exception e ) {
			if ( lazy.isDebug() ) {
				FLog.leaveBreadCrumb( "attainInstance Exception: %s", e.getMessage() );
			}
			throw FuelInjector.doFailure( lazy, e );
		}
	}

	static FuelInjectionException doFailure( Lazy lazy, Exception exception ) {
		return doFailure( lazy, new FuelInjectionException( exception ) );
	}

	static FuelInjectionException doFailure( Lazy lazy, FuelInjectionException exception ) {
		if ( isInitialized() ) {
			getFuelModule().onFailure( lazy, exception );
			return exception;
		}
		throw exception;
	}

	/**
	 * The Process Id Fuel was {@link #initializeModule(FuelModule)} with<br>
	 * It is required that Fuel be initialized from the main thread.
	 *
	 * @return id of the main thread.
	 */
	public static final long getPid() {
		return injector.mainThreadId;
	}

	/**
	 * @return true when the calling thread is the same threadId that called {@link #initializeModule(FuelModule)}
	 */
	public static final boolean inMainThread() {
		long id = Thread.currentThread().getId();
		if ( id != FuelInjector.getPid() ) {
			return false;
		}
		return true;
	}

	private static long mainThreadId;
	private FuelModule fuelModule;
	private final WeakHashMap<Context, Map<CacheKey, Object>> cache = new WeakHashMap<Context, Map<CacheKey, Object>>(); // context to injectable
	// map (not WeakReference of injectable)
	private final WeakHashMap<Context, WeakReference<Context>> contextToWeakContextCache = new WeakHashMap<Context, WeakReference<Context>>();
	static final long startTimeMillis = System.currentTimeMillis();

	private FuelInjector() {
	}

	/**
	 * Null when not yet initialized
	 */
	static FuelModule getFuelModule() {
		if ( injector != null ) {
			return injector.fuelModule;
		}
		return null;
	}

	private void clearContextCache( Context context ) {
		Lock lock = cacheLock.writeLock();
		try {
			lock.lock();
			cache.remove( context );
		} catch ( Exception e ) {
			FLog.e( e );
		} finally {
			lock.unlock();
		}
	}

	private static final Map<CacheKey, Object> EMPTY_MAP = new HashMap<CacheKey, Object>();

	/**
	 * @param context
	 * @param primeTheCacheEntry if true, an empty entry will be added for this context if not already present, false leaves it alone and returns
	 * empty
	 * @return
	 */
	private Map<CacheKey, Object> getCacheByContextNotThreadSafe( Context context, boolean primeTheCacheEntry ) {
		Map<CacheKey, Object> contextCache = cache.get( context );
		if ( contextCache == null ) {
			if ( primeTheCacheEntry ) {
				contextCache = new HashMap<CacheKey, Object>();
				cache.put( context, contextCache );
			} else {
				return EMPTY_MAP;
			}
		}
		return contextCache;
	}

	private Set<Object> getAllObjectsByContext( Context context ) {
		Lock lock = cacheLock.readLock();
		try {
			lock.lock();
			Set<Object> out = new HashSet<Object>( getCacheByContextNotThreadSafe( context, false ).values() );
			return out;
		} finally {
			lock.unlock();
		}
	}

	private Set<Object> getAllObjects() {
		Lock lock = cacheLock.readLock();
		try {
			lock.lock();
			Set<Object> out = new HashSet<Object>();
			Set<Entry<Context, Map<CacheKey, Object>>> entries = cache.entrySet();
			// for ( Entry<Context, Map<CacheKey, Object>> entry : entries ) {
			// out.addAll( entry.getValue().values() );
			// }
			for ( Context context : cache.keySet() ) {
				out.addAll( getCacheByContextNotThreadSafe( context, false ).values() );
			}
			return out;
		} finally {
			lock.unlock();
		}
	}

	private Object getObjectByContextType( Context context, CacheKey key ) {
		Lock lock = cacheLock.readLock();
		try {
			lock.lock();
			Map<CacheKey, Object> contextCache = getCacheByContextNotThreadSafe( context, false );
			Object obj = contextCache.get( key );
			return obj;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * @param value instance not lazy, cannot be null
	 */
	private void putObjectByContextType( Context context, CacheKey key, Object value ) {
		Lock lock = cacheLock.writeLock();
		try {
			lock.lock();
			Map<CacheKey, Object> contextCache = getCacheByContextNotThreadSafe( context, true );
			contextCache.put( key, value );
		} finally {
			lock.unlock();
		}
	}

	private static final Comparator<Object> COMPARATOR_CLASSNAME = new Comparator<Object>() {
		@Override
		public int compare( Object lhs, Object rhs ) {
			return lhs.getClass().getSimpleName().compareTo( rhs.getClass().getSimpleName() );
		}
	};

	private static final Comparator<CacheKey> COMPARATOR_CACHEKEY = new Comparator<CacheKey>() {
		@Override
		public int compare( CacheKey lhs, CacheKey rhs ) {
			String lhsString = lhs.getLeafType().getSimpleName() + lhs.getFlavor();
			String rhsString = rhs.getLeafType().getSimpleName() + rhs.getFlavor();
			return lhsString.compareTo( rhsString );
		}
	};
}

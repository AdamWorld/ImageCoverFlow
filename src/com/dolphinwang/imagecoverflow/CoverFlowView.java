/*
 * Copyright (C) 2013 Roy Wang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dolphinwang.imagecoverflow;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

/**
 * 
 * @author dolphinWang
 * @time 2013-11-29
 * 
 */
public class CoverFlowView<T extends CoverFlowAdapter> extends ViewGroup {

	public enum CoverFlowGravity {
		TOP, BOTTOM, CENTER_VERTICAL;
	};

	/**** static field ****/
	protected final int INVALID_POSITION = -1;

	// Used to indicate a no preference for a position type.
	static final int NO_POSITION = -1;

	// the visible views left and right
	protected int VISIBLE_VIEWS = 3;

	// space between each two of children
	protected final int CHILD_SPACING = -200;

	// ����alphaֵ
	private final int ALPHA_DATUM = 76;
	private int STANDARD_ALPHA;
	// �������ֵ
	private float FLANK_SPREAD = 0.05f;
	private static final float CARD_SCALE = 0.15f;
	private static float MOVE_POS_MULTIPLE = 3.0f;
	private static final int TOUCH_MINIMUM_MOVE = 5;
	private static final float MOVE_SPEED_MULTIPLE = 1;
	private static final float MAX_SPEED = 6.0f;
	private static final float FRICTION = 10.0f;
	/**** static field ****/

	private RecycleBin mRecycler;
	protected int mCoverFlowCenter;
	private T mAdapter;

	private int mVisibleChildCount;
	private int mItemCount;

	private int mBitmapCacheSize = -1;

	/**
	 * True if the data has changed since the last layout
	 */
	boolean mDataChanged;

	protected CoverFlowGravity mGravity;

	private Rect mCoverFlowPadding;

	// ����canvas��ͼ�µĿ��ݾ�
	private PaintFlagsDrawFilter mDrawFilter;

	// ���ڼ���3D�任�ı任����
	private Matrix mChildTransfromMatrix;

	// ��ͼʹ�õĻ���
	private Paint mDrawChildPaint;

	private RectF mTouchRect;

	private int mWidth;
	private boolean mTouchMoved;
	private float mTouchStartPos;
	private float mTouchStartX;
	private float mTouchStartY;

	private float mOffset;
	private int mLastOffset;

	private float mStartOffset;
	private long mStartTime;

	private float mStartSpeed;
	private float mDuration;
	private Runnable mAnimationRunnable;
	private VelocityTracker mVelocity;

	private int mChildHeight;
	private int mChildTranslateY;

	private int scaleCenter;

	private boolean reflectEnable = false;
	private boolean reflectShaderEnable = true;
	private float reflectHeight;
	private int reflectGap;

	private CoverFlowListener<T> mCoverFlowListener;

	public CoverFlowView(Context context) {
		super(context);
		init();
	}

	public CoverFlowView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initAttributes(context, attrs);
		init();
	}

	public CoverFlowView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initAttributes(context, attrs);
		init();
	}

	private void initAttributes(Context context, AttributeSet attrs) {
		TypedArray a = context.obtainStyledAttributes(attrs,
				R.styleable.ImageCoverFlowView);

		int totalVisibleChildren = a.getInt(
				R.styleable.ImageCoverFlowView_visibleImage, 2);
		if (totalVisibleChildren % 2 == 0) {
			throw new IllegalArgumentException(
					"visible image must be an odd number");
		}

		VISIBLE_VIEWS = totalVisibleChildren >> 1;

		reflectEnable = a.getBoolean(
				R.styleable.ImageCoverFlowView_enableReflection, false);
		if (reflectEnable) {
			reflectHeight = a.getFraction(
					R.styleable.ImageCoverFlowView_reflectionHeight, 100, 0,
					0.3f);
			if (reflectHeight > 100)
				reflectHeight = 100;
			reflectHeight /= 100;
			reflectGap = a.getDimensionPixelSize(
					R.styleable.ImageCoverFlowView_reflectionGap, 0);
			reflectShaderEnable = a
					.getBoolean(
							R.styleable.ImageCoverFlowView_reflectionShaderEnable,
							true);
		}

		mGravity = CoverFlowGravity.values()[a.getInt(
				R.styleable.ImageCoverFlowView_coverflowGravity, 2)];

		a.recycle();
	}

	private void init() {
		this.setWillNotDraw(false);

		mChildTransfromMatrix = new Matrix();

		mTouchRect = new RectF();

		mDrawChildPaint = new Paint();
		mDrawChildPaint.setAntiAlias(true); // ���û���Ϊ�޾��
		mDrawChildPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

		mCoverFlowPadding = new Rect();

		mDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG
				| Paint.FILTER_BITMAP_FLAG);
	}

	/**
	 * if subclass override this method, should call super method.
	 * 
	 * @param adapter
	 *            extends CoverFlowAdapter
	 */
	public void setAdapter(T adapter) {
		mAdapter = adapter;

		if (mAdapter != null) {
			mItemCount = mAdapter.getCount();
			if (mItemCount < (VISIBLE_VIEWS << 1) + 1) {
				throw new IllegalArgumentException(
						"total count in adapter must larger than visible images!");
			}

			mRecycler = new RecycleBin(mBitmapCacheSize);
		}

		resetList();

		requestLayout();
	}

	public T getAdapter() {
		return mAdapter;
	}

	public void setCoverFlowListener(CoverFlowListener<T> l) {
		mCoverFlowListener = l;
	}

	private void resetList() {
		removeAllViewsInLayout();
		if (mRecycler != null)
			mRecycler.clear();

		mChildHeight = 0;

		scaleCenter = 0;

		mOffset = 0;
		mLastOffset = -1;

		STANDARD_ALPHA = (255 - ALPHA_DATUM) / VISIBLE_VIEWS;

		if (mGravity == null) {
			mGravity = CoverFlowGravity.CENTER_VERTICAL;
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
	}

	/**
	 * ����������˼��㸸�ؼ��Ŀ��֮�⣬����Ҫ���Ǽ������������Ļ�ϵ�ͼƬ�Ŀ��
	 */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		mCoverFlowPadding.left = getPaddingLeft();
		mCoverFlowPadding.right = getPaddingRight();
		mCoverFlowPadding.top = getPaddingTop();
		mCoverFlowPadding.bottom = getPaddingBottom();

		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		int heightSize = MeasureSpec.getSize(heightMeasureSpec);

		int visibleCount = (VISIBLE_VIEWS << 1) + 1;

		int avaiblableHeight = heightSize - mCoverFlowPadding.top
				- mCoverFlowPadding.bottom;
		// if (reflectEnable) {
		// avaiblableHeight = avaiblableHeight - reflectHeight - reflectGap;
		// }

		int maxChildHeight = 0;

		for (int i = 0; i < visibleCount; ++i) {
			int childHeight = obtainImage(i).getHeight();

			maxChildHeight = (maxChildHeight < childHeight) ? childHeight
					: maxChildHeight;
		}

		if (heightMode == MeasureSpec.EXACTLY) {
			if (avaiblableHeight < maxChildHeight) {
				mChildHeight = avaiblableHeight;
			} else {
				mChildHeight = maxChildHeight;
			}
		} else {
			mChildHeight = maxChildHeight;
			if (avaiblableHeight > maxChildHeight) {
				heightSize = maxChildHeight + mCoverFlowPadding.top
						+ mCoverFlowPadding.bottom;
			}
		}

		// ������õ�Ӱ����ӰҲ��ͼƬ��һ����
		// if (reflectEnable) {
		// mChildHeight -= (reflectHeight + reflectGap);
		// }

		// ����gravity����y��λ��
		if (mGravity == CoverFlowGravity.CENTER_VERTICAL) {
			mChildTranslateY = (heightSize >> 1) - (mChildHeight >> 1);
		} else if (mGravity == CoverFlowGravity.TOP) {
			mChildTranslateY = mCoverFlowPadding.top;
		} else if (mGravity == CoverFlowGravity.BOTTOM) {
			mChildTranslateY = heightSize - mCoverFlowPadding.bottom
					- mChildHeight;
		}

		setMeasuredDimension(widthSize, heightSize);
		mVisibleChildCount = visibleCount;
		mWidth = widthSize;
		scaleCenter = mChildHeight >> 1;
	}

	/**
	 * subclass should never override this method, because all of child will
	 * draw on the canvas directly
	 */
	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		FLANK_SPREAD *= mWidth;
	}

	@Override
	protected void onDraw(Canvas canvas) {

		canvas.setDrawFilter(mDrawFilter);

		final float offset = mOffset;
		int i = 0;
		int mid = (int) Math.floor(offset + 0.5);

		int rightChild = (mVisibleChildCount % 2 == 0) ? (mVisibleChildCount >> 1) - 1
				: mVisibleChildCount >> 1;
		int leftChild = mVisibleChildCount >> 1;

		// draw the left children
		int startPos = mid - leftChild;
		for (i = startPos; i < mid; ++i) {
			drawChild(canvas, i, i - offset);
		}

		// draw the right children
		int endPos = mid + rightChild;
		for (i = endPos; i >= mid; --i) {
			drawChild(canvas, i, i - offset);
		}

		if (mLastOffset != (int) offset) {
			tileOnTop(getActuallyPosition((int) offset));
			mLastOffset = (int) offset;
		}

		super.onDraw(canvas);
	}

	protected final void drawChild(Canvas canvas, int position, float offset) {
		int actuallyPosition = getActuallyPosition(position);
		final Bitmap child = obtainImage(actuallyPosition);

		if (child != null && !child.isRecycled() && canvas != null) {

			makeChildTransfromMatrix(mChildTransfromMatrix, mDrawChildPaint,
					child, position, offset);

			canvas.drawBitmap(child, mChildTransfromMatrix, mDrawChildPaint);
		}
	}

	/**
	 * <ul>
	 * <li>��bitmap����α3d�任</li>
	 * <li>���������д���������������Զ�ͼƬ��3d�任�����Զ���</li>
	 * <li>��������д�����������ǧ������super.makeChildTransfromMatrix</li>
	 * </ul>
	 * 
	 * @param childTransfromMatrix
	 * @param mDrawChildPaint
	 *            ���Զ�paint����alphaֵ��ʵ�ָ���child��͸����
	 * @param child
	 * @param position
	 * @param offset
	 */
	private void makeChildTransfromMatrix(Matrix childTransfromMatrix,
			Paint mDrawChildPaint, Bitmap child, int position, float offset) {
		/*** ����3d�任 ***/
		mChildTransfromMatrix.reset();

		float scale = 1 - Math.abs(offset) * CARD_SCALE;

		// ��x���ƶ��ľ���Ӧ�ø���centerͼƬ����
		float translateX = 0;

		final float originalChildHeightScale = (float) mChildHeight
				/ child.getHeight();
		final float childHeightScale = originalChildHeightScale * scale;
		final int childWidth = (int) (child.getWidth() * childHeightScale);
		final int centerChildWidth = (int) (child.getWidth() * originalChildHeightScale);
		int leftSpace = ((mWidth >> 1) - mCoverFlowPadding.left)
				- (centerChildWidth >> 1);
		int rightSpace = (((mWidth >> 1) - mCoverFlowPadding.right) - (centerChildWidth >> 1));

		if (offset <= 0)
			translateX = ((float) leftSpace / VISIBLE_VIEWS)
					* (VISIBLE_VIEWS + offset) + mCoverFlowPadding.left;

		else
			translateX = mWidth - ((float) rightSpace / VISIBLE_VIEWS)
					* (VISIBLE_VIEWS - offset) - childWidth
					- mCoverFlowPadding.right;

		float alpha = (float) 255 - Math.abs(offset) * STANDARD_ALPHA;

		// ��alpha��һ����������ֹalphaС��0�����255���������
		if (alpha < 0) {
			alpha = 0;
		} else if (alpha > 255) {
			alpha = 255;
		}

		mDrawChildPaint.setAlpha((int) alpha);

		// �ƶ��任����
		mChildTransfromMatrix.preTranslate(0, -scaleCenter);

		// matrix�е�postxxxΪ˳��ִ�У��෴prexxxΪ����ִ��
		mChildTransfromMatrix.postScale(childHeightScale, childHeightScale);

		mChildTransfromMatrix.postTranslate(translateX, mChildTranslateY);

		// ���û�һ�����ᣬ�ڻ����任��������Լ�����Ҫ�ı任����
		getCustomTransformMatrix(mChildTransfromMatrix, mDrawChildPaint, child,
				position, offset);

		// ���ƶ���ȥ
		mChildTransfromMatrix.postTranslate(0, scaleCenter);
		/*** ����3d�任 ***/
	}

	/**
	 * <ul>
	 * <li>This is an empty method.</li>
	 * <li>Giving user a chance to make more transform base on standard.</li>
	 * </ul>
	 * 
	 * @param childTransfromMatrix
	 *            matrix to make transform
	 * @param mDrawChildPaint
	 *            paint, user can set alpha
	 * @param child
	 *            bitmap to draw
	 * @param position
	 * @param offset
	 *            offset to center(zero)
	 */
	protected void getCustomTransformMatrix(Matrix childTransfromMatrix,
			Paint mDrawChildPaint, Bitmap child, int position, float offset) {
	}

	private void tileOnTop(int position) {

		Bitmap child = mAdapter.getImage(position);

		final int tempReflectHeight = (int) (child.getHeight() * reflectHeight);
		final float childScale = (float) mChildHeight
				/ (child.getHeight() + tempReflectHeight + reflectGap);
		final int childHeight = (int) (mChildHeight * childScale - childScale
				* tempReflectHeight - childScale * reflectGap);
		final int childWidth = (int) (childScale * child.getWidth());

		mTouchRect.left = (mWidth >> 1) - (childWidth >> 1);
		mTouchRect.top = mChildTranslateY;
		mTouchRect.right = mTouchRect.left + childWidth;
		mTouchRect.bottom = mTouchRect.top + childHeight;

		if (mCoverFlowListener != null) {
			mCoverFlowListener.tileOnTop(this, position, mTouchRect.left,
					mTouchRect.top, mTouchRect.right, mTouchRect.bottom);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		int action = event.getAction();
		switch (action) {
		case MotionEvent.ACTION_DOWN:
			touchBegan(event);
			return true;
		case MotionEvent.ACTION_MOVE:
			touchMoved(event);
			return true;
		case MotionEvent.ACTION_UP:
			touchEnded(event);
			return true;
		}

		return false;
	}

	private void touchBegan(MotionEvent event) {
		endAnimation();

		float x = event.getX();
		mTouchStartX = x;
		mTouchStartY = event.getY();
		mStartTime = AnimationUtils.currentAnimationTimeMillis();
		mStartOffset = mOffset;

		mTouchMoved = false;

		mTouchStartPos = (x / mWidth) * MOVE_POS_MULTIPLE - 5;
		mTouchStartPos /= 2;

		mVelocity = VelocityTracker.obtain();
		mVelocity.addMovement(event);
	}

	private void touchMoved(MotionEvent event) {
		float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
		pos /= 2;

		if (!mTouchMoved) {
			float dx = Math.abs(event.getX() - mTouchStartX);
			float dy = Math.abs(event.getY() - mTouchStartY);

			if (dx < TOUCH_MINIMUM_MOVE && dy < TOUCH_MINIMUM_MOVE)
				return;

			mTouchMoved = true;
		}

		mOffset = mStartOffset + mTouchStartPos - pos;

		invalidate();
		mVelocity.addMovement(event);
	}

	private void touchEnded(MotionEvent event) {
		float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
		pos /= 2;

		if (mTouchMoved) {
			mStartOffset += mTouchStartPos - pos;
			mOffset = mStartOffset;

			mVelocity.addMovement(event);

			mVelocity.computeCurrentVelocity(1000);
			double speed = mVelocity.getXVelocity();

			// �ٶȵ���
			speed = (speed / mWidth) * MOVE_SPEED_MULTIPLE;
			if (speed > MAX_SPEED)
				speed = MAX_SPEED;
			else if (speed < -MAX_SPEED)
				speed = -MAX_SPEED;

			startAnimation(-speed);
		} else {
			if (mTouchRect != null) {
				if (mTouchRect.contains(event.getX(), event.getY())
						&& mCoverFlowListener != null) {
					mCoverFlowListener.topTileClicked(this,
							getActuallyPosition((int) mOffset));
				}
			}
		}

		// �ͷ�mVelocity���ڴ�
		mVelocity.clear();
		mVelocity.recycle();
	}

	private void startAnimation(double speed) {
		if (mAnimationRunnable != null)
			return;

		double delta = speed * speed / (FRICTION * 2);
		if (speed < 0)
			delta = -delta;

		double nearest = mStartOffset + delta;
		nearest = Math.floor(nearest + 0.5);

		mStartSpeed = (float) Math.sqrt(Math.abs(nearest - mStartOffset)
				* FRICTION * 2);
		if (nearest < mStartOffset)
			mStartSpeed = -mStartSpeed;

		mDuration = Math.abs(mStartSpeed / FRICTION);
		mStartTime = AnimationUtils.currentAnimationTimeMillis();

		mAnimationRunnable = new Runnable() {
			@Override
			public void run() {
				driveAnimation();
			}
		};
		post(mAnimationRunnable);
	}

	private void driveAnimation() {
		float elapsed = (AnimationUtils.currentAnimationTimeMillis() - mStartTime) / 1000.0f;
		if (elapsed >= mDuration)
			endAnimation();
		else {
			updateAnimationAtElapsed(elapsed);
			post(mAnimationRunnable);
		}
	}

	private void endAnimation() {
		if (mAnimationRunnable != null) {
			mOffset = (float) Math.floor(mOffset + 0.5);

			invalidate();

			removeCallbacks(mAnimationRunnable);
			mAnimationRunnable = null;
		}
	}

	private void updateAnimationAtElapsed(float elapsed) {
		if (elapsed > mDuration)
			elapsed = mDuration;

		float delta = Math.abs(mStartSpeed) * elapsed - FRICTION * elapsed
				* elapsed / 2;
		if (mStartSpeed < 0)
			delta = -delta;

		mOffset = mStartOffset + delta;
		invalidate();
	}

	/**
	 * �ѻ�ͼʱʹ�õ�indexת����adapter�е�position
	 * 
	 * @param position
	 *            position in adapter
	 * @return
	 */
	private int getActuallyPosition(int position) {
		int max = mAdapter.getCount();

		position += VISIBLE_VIEWS;
		while (position < 0 || position >= max) {
			if (position < 0) {
				position += max;
			} else if (position >= max) {
				position -= max;
			}
		}

		return position;
	}

	private Bitmap obtainImage(int position) {
		Bitmap bitmap = mRecycler.getCachedBitmap(position);

		if (bitmap == null || bitmap.isRecycled()) {
			bitmap = mAdapter.getImage(position);

			if (reflectEnable) {
				Bitmap bitmapWithReflect = BitmapUtils.createReflectedBitmap(
						bitmap, reflectHeight, reflectGap, reflectShaderEnable);

				if (bitmapWithReflect != null) {
					mRecycler.addBitmap2Cache(position, bitmapWithReflect);

					return bitmapWithReflect;
				} else {
					mRecycler.addBitmap2Cache(position, bitmap);
				}
			} else {

				mRecycler.addBitmap2Cache(position, bitmap);

			}

		}

		return bitmap;
	}

	public void setBitmapCacheSize(int cacheSize) {
		mBitmapCacheSize = cacheSize;
	}

	/**
	 * user is better to set a density of screen to make picture's movement more
	 * comfortable
	 * 
	 * @param density
	 */
	public void setScreenDensity(int density) {
		float temp = MOVE_POS_MULTIPLE * density;
		MOVE_POS_MULTIPLE = temp;
	}

	public void setVisibleImage(int count) {
		if (count % 2 != 0) {
			throw new IllegalArgumentException(
					"visible image must be an odd number");
		}

		VISIBLE_VIEWS = count / 2;
		STANDARD_ALPHA = (255 - ALPHA_DATUM) / VISIBLE_VIEWS;
	}

	public void setCoverFlowGravity(CoverFlowGravity gravity) {
		mGravity = gravity;
	}

	public void enableReflection(boolean enable) {
		this.reflectEnable = enable;
	}

	public void enableReflectionShader(boolean enable) {
		reflectShaderEnable = enable;
	}

	public void setReflectionHeight(int fraction) {
		if (fraction < 0)
			fraction = 30;
		else if (fraction > 100)
			fraction = 100;

		reflectHeight = 100;
	}

	public void setReflectionGap(int gap) {
		if (gap < 0)
			gap = 0;

		reflectGap = gap;
	}

	class RecycleBin {
		private int cacheSize = -1;

		public RecycleBin(int size) {
			cacheSize = size;
		}

		// 4mb��ͼƬ����
		@SuppressLint("NewApi")
		final LruCache<Integer, Bitmap> bitmapCache = new LruCache<Integer, Bitmap>(
				cacheSize == -1 ? getCacheSize(getContext()) : cacheSize) {
			@Override
			protected int sizeOf(Integer key, Bitmap bitmap) {
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR1) {
					return bitmap.getRowBytes() * bitmap.getHeight();
				} else {
					return bitmap.getByteCount();
				}
			}

			@Override
			protected void entryRemoved(boolean evicted, Integer key,
					Bitmap oldValue, Bitmap newValue) {
				if (evicted && oldValue != null && !oldValue.isRecycled()) {
					oldValue.recycle();
					oldValue = null;
				}
			}
		};

		public Bitmap getCachedBitmap(int position) {
			return bitmapCache.get(position);
		}

		public void addBitmap2Cache(int position, Bitmap b) {
			bitmapCache.put(position, b);
			Runtime.getRuntime().gc();
		}

		public void clear() {
			bitmapCache.evictAll();
		}

		private int getCacheSize(Context context) {
			// According to the phone memory, set a proper cache size for LRU
			// cache
			// dynamically.
			final ActivityManager am = (ActivityManager) context
					.getSystemService(Context.ACTIVITY_SERVICE);
			final int memClass = am.getMemoryClass();

			int cacheSize;
			if (memClass <= 24) {
				cacheSize = (memClass << 20) / 24;
			} else if (memClass <= 36) {
				cacheSize = (memClass << 20) / 18;
			} else if (memClass <= 48) {
				cacheSize = (memClass << 20) / 12;
			} else {
				cacheSize = (memClass << 20) >> 3;
			}
			Log.e(VIEW_LOG_TAG, "cacheSize == " + cacheSize);
			return cacheSize;
		}
	}

	public static interface CoverFlowListener<V extends CoverFlowAdapter> {
		public void tileOnTop(final CoverFlowView<V> coverFlowView,
				int position, float left, float top, float right, float bottom);

		public void topTileClicked(final CoverFlowView<V> coverFlowView,
				int position);
	}
}

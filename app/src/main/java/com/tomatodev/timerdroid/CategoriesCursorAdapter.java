package com.tomatodev.timerdroid;

import android.content.Context;
import android.database.Cursor;
import android.graphics.ColorMatrixColorFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.tomatodev.timerdroid.persistence.DbAdapter;

public class CategoriesCursorAdapter extends CursorAdapter {
    // Map built-in artwork from luminance to warm brown and tan, retaining alpha.
    private static final ColorMatrixColorFilter WARM_ART = new ColorMatrixColorFilter(new float[] {
            .213f * .61f, .715f * .61f, .072f * .61f, 0, 51,
            .213f * .54f, .715f * .54f, .072f * .54f, 0, 37,
            .213f * .46f, .715f * .46f, .072f * .46f, 0, 31,
            0, 0, 0, 1, 0
    });

	public CategoriesCursorAdapter(Context context, Cursor c) {
		super(context, c);
	}

	@Override
	public void bindView(View view, Context context, Cursor cursor) {
		
		ImageView imageView = (ImageView) view.findViewById(R.id.categories_image);
		String imageUri = cursor.getString(cursor.getColumnIndexOrThrow(DbAdapter.CATEGORIES_KEY_IMAGE));
        if (imageUri != null && imageUri.contains("://")) {
            imageView.clearColorFilter();
            imageView.setImageBitmap(Utilities.decodeFile(context, imageUri));
        } else {
            int icon = imageUri == null ? 0 : context.getResources().getIdentifier(
                    imageUri, "drawable", context.getPackageName());
            imageView.setImageResource(icon != 0 ? icon : R.drawable.collection);
            imageView.setColorFilter(WARM_ART);
        }

		TextView name = (TextView) view.findViewById(R.id.categories_name);
		name.setText(cursor.getString(cursor.getColumnIndexOrThrow(DbAdapter.CATEGORIES_KEY_NAME)));
	}

	@Override
	public View newView(Context context, Cursor cursor, ViewGroup parent) {
		LayoutInflater inflater = LayoutInflater.from(context);
		View v = inflater.inflate(R.layout.categories, parent, false);
//		bindView(v, context, cursor);
		return v;
	}
	
	
	


}

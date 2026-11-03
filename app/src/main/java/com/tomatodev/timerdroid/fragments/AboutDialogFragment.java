package com.tomatodev.timerdroid.fragments;

import android.support.v7.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.tomatodev.timerdroid.R;

public class AboutDialogFragment extends DialogFragment {

	public static AboutDialogFragment newInstance() {
		AboutDialogFragment fragment = new AboutDialogFragment();
		return fragment;
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		AlertDialog.Builder builder;
		AlertDialog alertDialog;

		Context mContext = getActivity();
		LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View layout = inflater.inflate(R.layout.about_dialog, (ViewGroup) getActivity().findViewById(R.id.main_layout));

		TextView tv = layout.findViewById(R.id.websiteTextView);
		tv.setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView) layout.findViewById(R.id.textView2)).setText(com.tomatodev.timerdroid.BuildConfig.VERSION_NAME);

		builder = new AlertDialog.Builder(mContext);
		builder.setView(layout);
		builder.setPositiveButton(R.string.menu_about_button_text, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {

			}
		});
        builder.setNeutralButton(R.string.about_haoke_site, (dialog, which) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://haoke.org")));
            } catch (ActivityNotFoundException unavailable) {
                android.widget.Toast.makeText(mContext, R.string.updates_browser_unavailable,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
		alertDialog = builder.create();
		alertDialog.setTitle(R.string.menu_about);
		return alertDialog;
	}

}

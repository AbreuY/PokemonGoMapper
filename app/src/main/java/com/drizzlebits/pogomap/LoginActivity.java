package com.drizzlebits.pogomap;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;

import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;
import com.pokegoapi.auth.GoogleAuthTokenJson;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.GoogleLoginSecrets;
import com.squareup.moshi.Moshi;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * A login screen that offers login via email/password.
 */
public class LoginActivity extends AppCompatActivity implements GoogleApiClient.OnConnectionFailedListener {
    private static final String TAG = LoginActivity.class.getSimpleName();

    private static final String PREFS_KEY_VERSION = "version";
    
    private static final int REQUEST_CODE_LOGIN_GOOGLE = 1000;

    /**
     * Keep track of the login task to ensure we can cancel it if requested.
     */
    private UserLoginTask mAuthTask = null;

    // UI references.
    private AutoCompleteTextView mEmailView;
    private EditText mPasswordView;
    private View mProgressView;
    private View mLoginFormView;

    private PokemonNetwork mPokemonNetwork;
    private OkHttpClient mHttpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Set up the login form.
        mEmailView = (AutoCompleteTextView) findViewById(R.id.email);

        mPasswordView = (EditText) findViewById(R.id.password);
        mPasswordView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int id, KeyEvent keyEvent) {
                if (id == R.id.login || id == EditorInfo.IME_NULL) {
                    attemptLogin();
                    return true;
                }
                return false;
            }
        });

        mHttpClient = new OkHttpClient();

        Button mPTCSignInButton = (Button) findViewById(R.id.ptc_sign_in_button);
        mPTCSignInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptLogin();
            }
        });

        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);

        SignInButton signInButton = (SignInButton) findViewById(R.id.google_sign_in_button);
        signInButton.setSize(SignInButton.SIZE_WIDE);
        signInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent signInIntent = new Intent(LoginActivity.this, GoogleLoginActivity.class);
                startActivityForResult(signInIntent, REQUEST_CODE_LOGIN_GOOGLE);
            }
        });

        int versionCode = 0;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // How?
        }

        final Thread autoLoginThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mPokemonNetwork = PokemonNetwork.getInstance(getApplicationContext());
                if (mPokemonNetwork.trySavedLogin()) {
                    startActivity(new Intent(getApplicationContext(), MapsActivity.class));
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showProgress(false);
                    }
                });
            }
        });

        SharedPreferences prefs = getSharedPreferences(TAG, MODE_PRIVATE);
        if (prefs.getInt(PREFS_KEY_VERSION, 0) != versionCode) {
            new AlertDialog.Builder(this)
                    .setMessage(Spannable.Factory.getInstance().newSpannable(getString(R.string.change_log)))
                    .setTitle("Change Log")
                    .setPositiveButton("Close", null)
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            showProgress(true);
                            autoLoginThread.start();
                        }
                    })
                    .show();
        } else {
            showProgress(true);
            autoLoginThread.start();
        }
        prefs.edit().putInt(PREFS_KEY_VERSION, versionCode).apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode == REQUEST_CODE_LOGIN_GOOGLE) {
                if (data == null || data.getStringExtra(GoogleLoginActivity.EXTRA_CODE) == null) {
                    Toast.makeText(LoginActivity.this, "Unable to log into Google", Toast.LENGTH_SHORT).show();
                    return;
                }

                showProgress(true);
                String code = data.getStringExtra(GoogleLoginActivity.EXTRA_CODE);

                RequestBody body = new FormBody.Builder()
                        .add("code", code)
                        .add("client_id", GoogleLoginSecrets.CLIENT_ID)
                        .add("client_secret", GoogleLoginSecrets.SECRET)
                        .add("redirect_uri", "http://127.0.0.1:9004")
                        .add("grant_type", "authorization_code")
                        .build();

                Request req = new Request.Builder()
                        .url(GoogleLoginSecrets.OAUTH_TOKEN_ENDPOINT)
                        .method("POST", body)
                        .build();

                mHttpClient.newCall(req).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showProgress(false);
                                Toast.makeText(LoginActivity.this, "Unable to log into Google", Toast.LENGTH_SHORT).show();
                            }
                        });
                        FirebaseCrash.report(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        Moshi moshi = (new com.squareup.moshi.Moshi.Builder()).build();
                        GoogleAuthTokenJson token = moshi.adapter(GoogleAuthTokenJson.class).fromJson(response.body().string());
                        mPokemonNetwork = PokemonNetwork.getInstance(getApplicationContext());
                        final boolean success = token.getIdToken() != null && token.getRefreshToken() != null;
                        mPokemonNetwork.loginGoogle(token.getIdToken(), token.getRefreshToken());
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showProgress(false);
                                if (success) {
                                    startActivity(new Intent(getApplicationContext(), MapsActivity.class));
                                } else {
                                    Toast.makeText(LoginActivity.this, "Unable to log in", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                });
            }
        } catch (Exception e) {
            // Feels so dirty, but the stack trace isn't telling me shit, so I need to catch all
            FirebaseCrash.report(new Throwable(e));
            Toast.makeText(LoginActivity.this, "Unable to log into Google", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Attempts to sign in or register the account specified by the login form.
     * If there are form errors (invalid email, missing fields, etc.), the
     * errors are presented and no actual login attempt is made.
     */
    private void attemptLogin() {
        if (mAuthTask != null) {
            return;
        }

        // Reset errors.
        mEmailView.setError(null);
        mPasswordView.setError(null);

        // Store values at the time of the login attempt.
        String email = mEmailView.getText().toString();
        String password = mPasswordView.getText().toString();

        boolean cancel = false;
        View focusView = null;

        // Check for a valid password, if the user entered one.
        if (TextUtils.isEmpty(password)) {
            mPasswordView.setError(getString(R.string.error_invalid_password));
            focusView = mPasswordView;
            cancel = true;
        }

        // Check for a valid email address.
        if (TextUtils.isEmpty(email)) {
            mEmailView.setError(getString(R.string.error_field_required));
            focusView = mEmailView;
            cancel = true;
        }

        if (cancel) {
            // There was an error; don't attempt login and focus the first
            // form field with an error.
            focusView.requestFocus();
        } else {
            // Show a progress spinner, and kick off a background task to
            // perform the user login attempt.
            showProgress(true);
            mAuthTask = new UserLoginTask(email, password);
            mAuthTask.execute((Void) null);
        }
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(
                    show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Toast.makeText(LoginActivity.this, "Unable to connect to Google Play Services", Toast.LENGTH_SHORT).show();
    }

    /**
     * Represents an asynchronous login/registration task used to authenticate
     * the user.
     */
    public class UserLoginTask extends AsyncTask<Void, Void, Boolean> {

        private final String mEmail;
        private final String mPassword;

        UserLoginTask(String email, String password) {
            mEmail = email;
            mPassword = password;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            PokemonNetwork pokemonNetwork = PokemonNetwork.getInstance(getApplicationContext());
            return pokemonNetwork.loginPTC(mEmail, mPassword);
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            mAuthTask = null;
            showProgress(false);

            if (success) {
                startActivity(new Intent(getApplicationContext(), MapsActivity.class));
            } else {
                mPasswordView.setError(getString(R.string.error_incorrect_password));
                mPasswordView.requestFocus();
            }
        }

        @Override
        protected void onCancelled() {
            mAuthTask = null;
            showProgress(false);
        }
    }
}


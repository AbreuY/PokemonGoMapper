package com.pokegomapco.pokemongomapper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;

import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
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

        showProgress(true);
        new Thread(new Runnable() {
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
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_LOGIN_GOOGLE) {
            showProgress(true);
            String code = data.getStringExtra(GoogleLoginActivity.EXTRA_CODE);

            RequestBody body = new FormBody.Builder()
                    .add("code", code)
                    .add("client_id", GoogleLogin.CLIENT_ID)
                    .add("client_secret", GoogleLogin.SECRET)
                    .add("redirect_uri", "http://127.0.0.1:9004")
                    .add("grant_type", "authorization_code")
                    .build();

            Request req = new Request.Builder()
                    .url(GoogleLogin.OAUTH_TOKEN_ENDPOINT)
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
                    GoogleAuthTokenJson token = new Gson().fromJson(response.body().string(), GoogleAuthTokenJson.class);
                    mPokemonNetwork = PokemonNetwork.getInstance(getApplicationContext());
                    mPokemonNetwork.loginGoogle(token.getId_token());
                    startActivity(new Intent(getApplicationContext(), MapsActivity.class));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showProgress(false);
                        }
                    });
                }
            });
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


package com.artemshvadskiy.pokemongomapper;

import POGOProtos.Networking.EnvelopesOuterClass;
import android.accounts.Account;
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
//import com.google.android.gms.auth.GoogleAuthException;
//import com.google.android.gms.auth.GoogleAuthUtil;
//import com.google.android.gms.auth.UserRecoverableAuthException;
//import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
//import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
//import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pokegoapi.auth.GoogleAuthJson;
import com.pokegoapi.auth.GoogleAuthTokenJson;
import com.pokegoapi.exceptions.LoginFailedException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * A login screen that offers login via email/password.
 */
public class LoginActivity extends AppCompatActivity implements GoogleApiClient.OnConnectionFailedListener {
    private static final int REQUEST_CODE_PICK_ACCOUNT = 1000;
    private static final int REQUEST_CODE_AUTHORIZE_APP = 1001;

    /**
     * Keep track of the login task to ensure we can cancel it if requested.
     */
    private UserLoginTask mAuthTask = null;

    // UI references.
    private AutoCompleteTextView mEmailView;
    private EditText mPasswordView;
    private View mProgressView;
    private View mLoginFormView;

    private GoogleApiClient mGoogleApiClient;
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

        //mHttpClient = new OkHttpClient();

        Button mPTCSignInButton = (Button) findViewById(R.id.ptc_sign_in_button);
        mPTCSignInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptLogin();
            }
        });

        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);

        /*GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                //.requestServerAuthCode(GOOGLE_CLIENT_SECRET)
                .requestEmail()
                .build();

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .enableAutoManage(this, this)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .build();

        SignInButton signInButton = (SignInButton) findViewById(R.id.google_sign_in_button);
        signInButton.setSize(SignInButton.SIZE_WIDE);
        signInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
                startActivityForResult(signInIntent, REQUEST_CODE_PICK_ACCOUNT);
            }
        });*/

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



    /*@Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == REQUEST_CODE_PICK_ACCOUNT) {
            showProgress(true);
            GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);

            if (result.isSuccess()) {
                // Signed in successfully, show authenticated UI.
                GoogleSignInAccount acct = result.getSignInAccount();
                showProgress(true);
                new GetUsernameTask(acct.getEmail()).execute();
            } else {
                // Signed out, show unauthenticated UI.
                showProgress(false);
                Toast.makeText(LoginActivity.this, "Login error: " + result.getStatus().getStatusMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_AUTHORIZE_APP) {

        }
    }

    public class GetUsernameTask extends AsyncTask<Void, Void, Void> {
        String mEmail;

        GetUsernameTask(String name) {
            this.mEmail = name;
        }

        *//**
         * Executes the asynchronous job. This runs when you call execute()
         * on the AsyncTask instance.
         *//*
        @Override
        protected Void doInBackground(Void... params) {
            try {
                String token = fetchToken();
                if (token != null) {
                    PokemonNetwork.getInstance(getApplicationContext()).loginGoogle(token);
                    startActivity(new Intent(getApplicationContext(), MapsActivity.class));
                }
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), "Google login error: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
            return null;
        }

        *//**
         * Gets an authentication token from Google and handles any
         * GoogleAuthException that may occur.
         *//*
        protected String fetchToken() throws IOException {
            *//*try {
                return GoogleAuthUtil.getToken(getApplicationContext(), new Account(mEmail, "com.google"), SCOPE);
            } catch (UserRecoverableAuthException e) {
                // GooglePlayServices.apk is either old, disabled, or not present
                // so we need to show the user some UI in the activity to recover.
                Toast.makeText(getApplicationContext(), "Google login error: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            } catch (GoogleAuthException e) {
                // Some other type of unrecoverable exception has occurred.
                // Report and log the error as appropriate for your app.
                Toast.makeText(getApplicationContext(), "Google login error: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
            return null;*//*



            *//*HttpURLConnection urlConnection = null;

            try {
                String scope = "audience:server:client_id:" + GOOGLE_CLIENT_SECRET + ":oauth2:https://www.googleapis.com/auth/userinfo.profile";//client_id:" + GOOGLE_CLIENT_SECRET + ":client_secret:" + GOOGLE_API_KEY;// + ":api_scope:https://www.googleapis.com/auth/userinfo.email";

                //String SCOPE =  + SERVER_CLIENT_ID;
                //String scope = "audience:server:client_id:" + GOOGLE_CLIENT_SECRET;// + ":api_scope:https://www.googleapis.com/auth/plus.login";
                //URL url = new URL("https://www.googleapis.com/plus/v1/people/me");
                String sAccessToken = GoogleAuthUtil.getToken(getApplicationContext(), new Account(mEmail, "com.google"), scope);
                //+ Scopes.PLUS_LOGIN + " https://www.googleapis.com/auth/plus.profile.emails.read"

                *//**//*urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Authorization", "Bearer " + sAccessToken);

                String content = CharStreams.toString(new InputStreamReader(urlConnection.getInputStream(),
                        Charsets.UTF_8));

                if (!TextUtils.isEmpty(content)) {
                    JSONArray emailArray =  new JSONObject(content).getJSONArray("emails");

                    for (int i = 0; i < emailArray.length; i++) {
                        JSONObject obj = (JSONObject)emailArray.get(i);

                        // Find and return the primary email associated with the account
                        if (obj.getString("type") == "account") {
                            return obj.getString("value");
                        }
                    }
                }*//**//*
                return sAccessToken;
            } catch (UserRecoverableAuthException userAuthEx) {
                // Start the user recoverable action using the intent returned by
                // getIntent()
                startActivityForResult(userAuthEx.getIntent(), REQUEST_CODE_AUTHORIZE_APP);
                return null;
            } catch (Exception e) {
                // Handle error
                e.printStackTrace(); // Uncomment if needed during debugging.
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            return null;*//*

            try {
                return login(mEmail, mPasswordView.getText().toString());
            } catch (Exception e) {
                return null;
            }
        }

        public String login(String username, String password) throws LoginFailedException {
            try {
                HttpUrl e = HttpUrl.parse("https://accounts.google.com/o/oauth2/v2/auth").newBuilder().addQueryParameter("login_hint", username).addQueryParameter("client_id", "848232511240-73ri3t7plvk96pj4f85uj8otdat2alem.apps.googleusercontent.com").addQueryParameter("scope", "openid email https://www.googleapis.com/auth/userinfo.email").build();
                RequestBody reqBody = RequestBody.create((MediaType)null, new byte[0]);
                Request request = (new okhttp3.Request.Builder()).url(e).method("POST", reqBody).build();
                Response response = mHttpClient.newCall(request).execute();
                Gson gson = (new GsonBuilder()).create();
                GoogleAuthJson googleAuth = (GoogleAuthJson)gson.fromJson(response.body().string(), GoogleAuthJson.class);
                System.out.println("Get user to go to:" + googleAuth.getVerification_url() + " and enter code:" + googleAuth.getUser_code());

                GoogleAuthTokenJson token;
                while((token = this.poll(googleAuth)) == null) {
                    Thread.sleep((long)(googleAuth.getInterval() * 1000));
                }

                System.out.println("Got token:" + token.getId_token());
                return token.getId_token();
                *//*EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo.Builder authbuilder = EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo.newBuilder();
                authbuilder.setProvider("google");
                authbuilder.setToken(EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo.JWT.newBuilder().setContents(token.getId_token()).setUnknown2(59).build());
                return authbuilder.build();*//*
            } catch (Exception var11) {
                var11.printStackTrace();
                throw new LoginFailedException();
            }
        }

        private GoogleAuthTokenJson poll(GoogleAuthJson json) throws URISyntaxException, IOException {
            HttpUrl url = HttpUrl.parse("https://www.googleapis.com/oauth2/v4/token").newBuilder().addQueryParameter("client_id", "848232511240-73ri3t7plvk96pj4f85uj8otdat2alem.apps.googleusercontent.com").addQueryParameter("client_secret", "NCjF1TLi2CcY6t5mt0ZveuL7").addQueryParameter("code", json.getDevice_code()).addQueryParameter("grant_type", "http://oauth.net/grant_type/device/1.0").addQueryParameter("scope", "openid email https://www.googleapis.com/auth/userinfo.email").build();
            RequestBody reqBody = RequestBody.create((MediaType)null, new byte[0]);
            Request request = (new okhttp3.Request.Builder()).url(url).method("POST", reqBody).build();
            Response response = mHttpClient.newCall(request).execute();
            Gson gson = (new GsonBuilder()).create();
            GoogleAuthTokenJson token = (GoogleAuthTokenJson)gson.fromJson(response.body().string(), GoogleAuthTokenJson.class);
            return token.getError() == null?token:null;
        }
    }*/

    /*private void pickUserAccount() {
        Intent intent = AccountPicker.newChooseAccountIntent(null, null, null, false, null, null, null, null);
        startActivityForResult(intent, REQUEST_CODE_PICK_ACCOUNT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_ACCOUNT) {
            // Receiving a result from the AccountPicker
            if (resultCode == RESULT_OK) {
                String email = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                // With the account name acquired, go get the auth token
                getUsername();
            } else if (resultCode == RESULT_CANCELED) {
                // The account picker dialog closed without selecting an account.
                // Notify users that they must pick an account to proceed.
                Toast.makeText(this, "Must pick account to login.", Toast.LENGTH_SHORT).show();
            }
        }
    }*/

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


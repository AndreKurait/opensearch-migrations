package com.rfs.common;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public interface AuthConfig {
    class NoAuth implements AuthConfig {
        public static final NoAuth INSTANCE = new NoAuth();
        private NoAuth() {}
    }

    class BasicAuth implements AuthConfig {
        public final String username;
        public final String password;

        public BasicAuth(String username, String password) {
            if (username == null || password == null) {
                throw new IllegalArgumentException("Both username and password must be provided");
            }
            this.username = username;
            this.password = password;
        }
    }

    class SigV4Auth implements AuthConfig {
        public final AwsCredentialsProvider awsCredentialsProvider;
        public final String awsRegion;
        public final String awsServiceName;

        public SigV4Auth(AwsCredentialsProvider awsCredentialsProvider, String awsRegion, String awsServiceName) {
            this.awsCredentialsProvider = awsCredentialsProvider;
            this.awsRegion = awsRegion;
            this.awsServiceName = awsServiceName;
        }
    }

}


// Copyright 2016 Google, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.firebase.jobdispatcher;

import static com.firebase.jobdispatcher.Constraint.compact;
import static com.firebase.jobdispatcher.Constraint.uncompact;
import static com.firebase.jobdispatcher.ExecutionDelegator.TAG;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
/**
 * JobCoder is a tool to encode and decode JobSpecs from Bundles.
 */
/* package */ final class JobCoder {
    private final boolean includeExtras;
    private final String prefix;

    JobCoder(String prefix, boolean includeExtras) {
        this.includeExtras = includeExtras;
        this.prefix = prefix;
    }

    @NonNull
    Bundle encode(@NonNull JobParameters jobParameters, @NonNull Bundle data) {
        if (data == null) {
            throw new IllegalArgumentException("Unexpected null Bundle provided");
        }

        data.putInt(prefix + BundleProtocol.PACKED_PARAM_LIFETIME,
            jobParameters.getLifetime());
        data.putBoolean(prefix + BundleProtocol.PACKED_PARAM_RECURRING,
            jobParameters.isRecurring());
        data.putBoolean(prefix + BundleProtocol.PACKED_PARAM_REPLACE_CURRENT,
            jobParameters.shouldReplaceCurrent());
        data.putString(prefix + BundleProtocol.PACKED_PARAM_TAG,
            jobParameters.getTag());
        data.putString(prefix + BundleProtocol.PACKED_PARAM_SERVICE,
            jobParameters.getService());
        data.putInt(prefix + BundleProtocol.PACKED_PARAM_CONSTRAINTS,
            compact(jobParameters.getConstraints()));

        if (includeExtras) {
            data.putBundle(prefix + BundleProtocol.PACKED_PARAM_EXTRAS,
                jobParameters.getExtras());
        }

        encodeTrigger(jobParameters.getTrigger(), data);
        encodeRetryStrategy(jobParameters.getRetryStrategy(), data);

        return data;
    }

    JobInvocation decodeIntentBundle(@NonNull Bundle bundle) {
        if (bundle == null) {
            Log.e(TAG, "Unexpected null Bundle provided");
            return null;
        }

        Bundle taskExtras = bundle.getBundle(GooglePlayJobWriter.REQUEST_PARAM_EXTRAS);
        if (taskExtras == null) {
            return null;
        }

        JobInvocation.Builder builder = decode(taskExtras);

        return builder.build();
    }

    @Nullable
    public JobInvocation.Builder decode(@NonNull Bundle data) {
        if (data == null) {
            throw new IllegalArgumentException("Unexpected null Bundle provided");
        }

        boolean recur = data.getBoolean(prefix + BundleProtocol.PACKED_PARAM_RECURRING);
        boolean replaceCur = data.getBoolean(prefix + BundleProtocol.PACKED_PARAM_REPLACE_CURRENT);
        int lifetime = data.getInt(prefix + BundleProtocol.PACKED_PARAM_LIFETIME);
        int[] constraints = uncompact(data.getInt(prefix + BundleProtocol.PACKED_PARAM_CONSTRAINTS));

        JobTrigger trigger = decodeTrigger(data);
        RetryStrategy retryStrategy = decodeRetryStrategy(data);

        String tag = data.getString(prefix + BundleProtocol.PACKED_PARAM_TAG);
        String service = data.getString(prefix + BundleProtocol.PACKED_PARAM_SERVICE);

        if (tag == null || service == null || trigger == null || retryStrategy == null) {
            return null;
        }

        JobInvocation.Builder builder = new JobInvocation.Builder();
        builder.setTag(tag);
        builder.setService(service);
        builder.setTrigger(trigger);
        builder.setRetryStrategy(retryStrategy);
        builder.setRecurring(recur);
        //noinspection WrongConstant
        builder.setLifetime(lifetime);
        //noinspection WrongConstant
        builder.setConstraints(constraints);
        builder.setReplaceCurrent(replaceCur);

        // repack the taskExtras
        builder.addExtras(data);
        return builder;
    }

    @NonNull
    private JobTrigger decodeTrigger(Bundle data) {
        switch (data.getInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_TYPE)) {
            case BundleProtocol.TRIGGER_TYPE_IMMEDIATE:
                return Trigger.NOW;

            case BundleProtocol.TRIGGER_TYPE_EXECUTION_WINDOW:
                return Trigger.executionWindow(
                    data.getInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_WINDOW_START),
                    data.getInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_WINDOW_END));

            default:
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Unsupported trigger.");
                }
                return null;
        }
    }

    private void encodeTrigger(JobTrigger trigger, Bundle data) {
        if (trigger == Trigger.NOW) {
            data.putInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_TYPE,
                BundleProtocol.TRIGGER_TYPE_IMMEDIATE);
        } else if (trigger instanceof JobTrigger.ExecutionWindowTrigger) {
            JobTrigger.ExecutionWindowTrigger t = (JobTrigger.ExecutionWindowTrigger) trigger;

            data.putInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_TYPE,
                BundleProtocol.TRIGGER_TYPE_EXECUTION_WINDOW);
            data.putInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_WINDOW_START,
                t.getWindowStart());
            data.putInt(prefix + BundleProtocol.PACKED_PARAM_TRIGGER_WINDOW_END,
                t.getWindowEnd());
        } else {
            throw new IllegalArgumentException("Unsupported trigger.");
        }
    }

    private RetryStrategy decodeRetryStrategy(Bundle data) {
        int policy = data.getInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_POLICY);
        if (policy != RetryStrategy.RETRY_POLICY_EXPONENTIAL
            && policy != RetryStrategy.RETRY_POLICY_LINEAR) {

            return RetryStrategy.DEFAULT_EXPONENTIAL;
        }

        //noinspection WrongConstant
        return new RetryStrategy(
            policy,
            data.getInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_INITIAL_BACKOFF_SECONDS),
            data.getInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_MAXIMUM_BACKOFF_SECONDS));
    }

    private void encodeRetryStrategy(RetryStrategy retryStrategy, Bundle data) {
        if (retryStrategy == null) {
            retryStrategy = RetryStrategy.DEFAULT_EXPONENTIAL;
        }

        data.putInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_POLICY,
            retryStrategy.getPolicy());
        data.putInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_INITIAL_BACKOFF_SECONDS,
            retryStrategy.getInitialBackoff());
        data.putInt(prefix + BundleProtocol.PACKED_PARAM_RETRY_STRATEGY_MAXIMUM_BACKOFF_SECONDS,
            retryStrategy.getMaximumBackoff());
    }
}

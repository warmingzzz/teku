/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.beaconrestapi.handlers.validator;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.COMMITTEE_INDEX;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.RES_BAD_REQUEST;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.RES_NOT_FOUND;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.RES_OK;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.SLOT;
import static tech.pegasys.teku.beaconrestapi.RestApiConstants.TAG_VALIDATOR;
import static tech.pegasys.teku.beaconrestapi.SingleQueryParameterUtils.getParameterValueAsInt;
import static tech.pegasys.teku.beaconrestapi.SingleQueryParameterUtils.getParameterValueAsUInt64;

import com.google.common.base.Throwables;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.plugin.openapi.annotations.HttpMethod;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiParam;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tech.pegasys.teku.api.ValidatorDataProvider;
import tech.pegasys.teku.api.schema.Attestation;
import tech.pegasys.teku.beaconrestapi.schema.BadRequest;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.provider.JsonProvider;

public class GetAttestation implements Handler {
  public static final String ROUTE = "/validator/attestation";

  private final ValidatorDataProvider provider;
  private final JsonProvider jsonProvider;

  public GetAttestation(final ValidatorDataProvider provider, final JsonProvider jsonProvider) {
    this.jsonProvider = jsonProvider;
    this.provider = provider;
  }

  @OpenApi(
      deprecated = true,
      path = ROUTE,
      method = HttpMethod.GET,
      summary = "Get an unsigned attestation for a slot from the current state.",
      tags = {TAG_VALIDATOR},
      queryParams = {
        @OpenApiParam(
            name = SLOT,
            description = "`uint64` Non-finalized slot for which to create the attestation.",
            required = true),
        @OpenApiParam(
            name = COMMITTEE_INDEX,
            type = Integer.class,
            description = "`Integer` Index of the committee making the attestation.",
            required = true)
      },
      description =
          "Returns an unsigned attestation for the block at the specified non-finalized slot.\n\n"
              + "This endpoint is not protected against slashing. Signing the returned attestation can result in a slashable offence.\n"
              + "Deprecated - use `/eth/v1/validator/attestation_data` instead.",
      responses = {
        @OpenApiResponse(
            status = RES_OK,
            content = @OpenApiContent(from = Attestation.class),
            description =
                "Returns an attestation object with a blank signature. The `signature` field should be replaced by a valid signature."),
        @OpenApiResponse(status = RES_BAD_REQUEST, description = "Invalid parameter supplied"),
        @OpenApiResponse(
            status = RES_NOT_FOUND,
            description = "An attestation could not be created for the specified slot.")
      })
  @Override
  public void handle(Context ctx) throws Exception {

    try {
      final Map<String, List<String>> parameters = ctx.queryParamMap();
      if (parameters.size() < 2) {
        throw new IllegalArgumentException(
            String.format("Please specify both %s and %s", SLOT, COMMITTEE_INDEX));
      }
      UInt64 slot = getParameterValueAsUInt64(parameters, SLOT);
      int committeeIndex = getParameterValueAsInt(parameters, COMMITTEE_INDEX);
      if (committeeIndex < 0) {
        throw new IllegalArgumentException(
            String.format("'%s' needs to be greater than or equal to 0.", COMMITTEE_INDEX));
      }

      ctx.result(
          provider
              .createUnsignedAttestationAtSlot(slot, committeeIndex)
              .thenApplyChecked(optionalAttestation -> serializeResult(ctx, optionalAttestation))
              .exceptionallyCompose(error -> handleError(ctx, error)));
    } catch (final IllegalArgumentException e) {
      ctx.result(jsonProvider.objectToJSON(new BadRequest(e.getMessage())));
      ctx.status(SC_BAD_REQUEST);
    }
  }

  private String serializeResult(final Context ctx, final Optional<Attestation> optionalAttestation)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (optionalAttestation.isPresent()) {
      return jsonProvider.objectToJSON(optionalAttestation.get());
    } else {
      ctx.status(SC_NOT_FOUND);
      return "";
    }
  }

  private CompletionStage<String> handleError(final Context ctx, final Throwable error) {
    if (Throwables.getRootCause(error) instanceof IllegalArgumentException) {
      ctx.status(SC_BAD_REQUEST);
      return SafeFuture.of(() -> jsonProvider.objectToJSON(new BadRequest(error.getMessage())));
    }
    return SafeFuture.failedFuture(error);
  }
}

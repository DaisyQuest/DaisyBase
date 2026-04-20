package dev.daisybase.demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/orders")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {
    @Inject
    EnterpriseService enterpriseService;

    @GET
    public List<EnterpriseModel.OrderSummary> orders() {
        return enterpriseService.orders();
    }

    @POST
    public Response createOrder(EnterpriseModel.CreateOrderRequest request) {
        try {
            return Response.status(Response.Status.CREATED)
                    .entity(enterpriseService.createOrder(request))
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new WebApplicationException(exception.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/{orderId}/fulfill")
    public EnterpriseModel.OrderSummary fulfillOrder(@PathParam("orderId") long orderId) {
        try {
            return enterpriseService.fulfillOrder(orderId);
        } catch (IllegalArgumentException exception) {
            throw new WebApplicationException(exception.getMessage(), Response.Status.BAD_REQUEST);
        }
    }
}

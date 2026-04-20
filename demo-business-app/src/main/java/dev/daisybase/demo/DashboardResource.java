package dev.daisybase.demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/dashboard")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class DashboardResource {
    @Inject
    EnterpriseService enterpriseService;

    @GET
    public EnterpriseModel.DashboardSnapshot dashboard() {
        return enterpriseService.dashboard();
    }
}

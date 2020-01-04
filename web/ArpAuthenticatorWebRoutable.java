package net.floodlightcontroller.sdn_arp_spoof_detection.web;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import net.floodlightcontroller.sdn_arp_spoof_detection.*;
import net.floodlightcontroller.restserver.RestletRoutable;

public class ArpAuthenticatorWebRoutable implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
		router.attach("/list",ArpEntryResource.class);
		return router;
	}

	@Override
	public String basePath() {
		return "/wm/arp";
	}
}

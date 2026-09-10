# Ansik VM deployment

The simple deployment path is Android -> `34.50.8.4:8088` -> Spring Boot -> Cloud SQL public IP over TLS.
Authorize only `34.50.8.4/32` in Cloud SQL and never authorize `0.0.0.0/0`. The root account is not used by the application.

## VM files

- `/opt/ansik/ansik.jar`: backend executable JAR
- `/opt/ansik/.env.properties`: copy of `.env.properties.example` containing real secrets
- `/etc/systemd/system/ansik.service`: copy of `deploy/ansik.service`

Create a system user named `ansik` and make `/opt/ansik` readable by that user. Bind the proxy only to `127.0.0.1`; never expose its local port publicly.

## Database initialization

Connect with MySQL Workbench as root and run these files in order:

1. `database/schema.sql`
2. `database/app-user-grants.sql`

After verifying the app, remove the temporary Workbench authorized network from Cloud SQL. Keep only the VM address `34.50.8.4/32`.

## Network

The current Android build targets `http://34.50.8.4:8088/`. Until HTTPS is added, create a narrowly scoped GCP ingress firewall rule for TCP 8088. For public release, put Nginx and a domain with TLS in front of the app, switch Android to the HTTPS URL, and remove cleartext traffic support.

After startup, verify the API with `http://34.50.8.4:8088/actuator/health`. A healthy server returns `{"status":"UP"}` without exposing database details.

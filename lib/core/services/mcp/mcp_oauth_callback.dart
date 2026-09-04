import 'mcp_oauth_callback_stub.dart'
    if (dart.library.io) 'mcp_oauth_callback_io.dart'
    as implementation;
import 'mcp_oauth_callback_types.dart';

export 'mcp_oauth_callback_types.dart';

Future<McpOAuthCallback> openMcpOAuthCallback(Uri authorizationServer) =>
    implementation.openMcpOAuthCallback(authorizationServer);

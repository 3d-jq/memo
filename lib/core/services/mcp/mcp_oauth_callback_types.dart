typedef McpOAuthUrlLauncher = Future<bool> Function(Uri uri);

final class McpOAuthCallbackException implements Exception {
  const McpOAuthCallbackException(this.message, {this.cancelled = false});

  final String message;
  final bool cancelled;

  @override
  String toString() => message;
}

abstract interface class McpOAuthCallback {
  Uri get redirectUri;

  Future<Uri> authorize(
    Uri authorizationUrl,
    Duration timeout,
    McpOAuthUrlLauncher launchAuthorizationUrl,
  );

  Future<Uri> waitForCallback(Duration timeout);

  Future<void> close();
}

typedef McpOAuthCallbackFactory =
    Future<McpOAuthCallback> Function(Uri authorizationServer);

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:Kelivo/core/providers/model_provider.dart';
import 'package:Kelivo/core/providers/settings_provider.dart';

void main() {
  test(
    'connection test applies provider request and lets model override it',
    () async {
      late HttpHeaders receivedHeaders;
      late Map<String, dynamic> receivedBody;
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));

      server.listen((request) async {
        receivedHeaders = request.headers;
        receivedBody =
            (jsonDecode(await utf8.decoder.bind(request).join()) as Map)
                .cast<String, dynamic>();
        request.response.statusCode = HttpStatus.ok;
        request.response.headers.contentType = ContentType.json;
        request.response.write('{}');
        await request.response.close();
      });

      final config = ProviderConfig(
        id: 'ConnectionTest',
        enabled: true,
        name: 'ConnectionTest',
        apiKey: 'test-key',
        baseUrl: 'http://${server.address.address}:${server.port}',
        providerType: ProviderKind.openai,
        chatPath: '/chat/completions',
        customHeaders: const [
          {'name': 'X-Level', 'value': 'provider'},
          {'name': 'X-Provider', 'value': 'provider-only'},
        ],
        customBody: const [
          {'key': 'shared', 'value': 'provider'},
          {'key': 'providerOnly', 'value': 'true'},
        ],
        modelOverrides: const {
          'test-model': {
            'headers': [
              {'name': 'x-level', 'value': 'model'},
            ],
            'body': [
              {'key': 'shared', 'value': 'model'},
            ],
          },
        },
      );

      await ProviderManager.testConnection(config, 'test-model');

      expect(receivedHeaders.value('x-level'), 'model');
      expect(receivedHeaders.value('x-provider'), 'provider-only');
      expect(receivedBody['shared'], 'model');
      expect(receivedBody['providerOnly'], isTrue);
    },
  );
}

import 'dart:convert';

import 'package:flutter/material.dart';

import '../../../../l10n/app_localizations.dart';
import '../search_service.dart';

/// TinyFish Search API. Requires `X-API-Key`. Fetch/Scrape is not implemented.
class TinyFishSearchService extends SearchService<TinyFishOptions> {
  TinyFishSearchService({super.client});

  @override
  String get name => 'TinyFish';

  @override
  Widget description(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return Text(
      l10n.searchProviderTinyFishDescription,
      style: const TextStyle(fontSize: 12),
    );
  }

  @override
  Future<SearchResult> search({
    required String query,
    required SearchCommonOptions commonOptions,
    required TinyFishOptions serviceOptions,
  }) async {
    try {
      final apiKey = serviceOptions
          .effectiveApiKey(serviceOptions.apiKey)
          .trim();
      if (apiKey.isEmpty) {
        throw Exception('TinyFish API key is required');
      }

      final params = <String, String>{
        'query': query,
        if (serviceOptions.location.trim().isNotEmpty)
          'location': serviceOptions.location.trim(),
        if (serviceOptions.language.trim().isNotEmpty)
          'language': serviceOptions.language.trim(),
        if (serviceOptions.includeDomains.trim().isNotEmpty)
          'include_domains': serviceOptions.includeDomains.trim(),
        if (serviceOptions.excludeDomains.trim().isNotEmpty)
          'exclude_domains': serviceOptions.excludeDomains.trim(),
      };

      final uri = Uri.parse(
        serviceOptions.resolvedUrl,
      ).replace(queryParameters: params);

      final response = await withHttpClient(
        (client) => client
            .get(uri, headers: {'X-API-Key': apiKey})
            .timeout(Duration(milliseconds: commonOptions.timeout)),
      );

      if (response.statusCode != 200) {
        throw Exception(
          'API request failed: ${response.statusCode} ${response.body}',
        );
      }

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final results = (data['results'] as List?) ?? const <dynamic>[];
      final items = results.take(commonOptions.resultSize).map((item) {
        final m = (item as Map).cast<String, dynamic>();
        return SearchResultItem(
          title: (m['title'] ?? '').toString(),
          url: (m['url'] ?? '').toString(),
          text: (m['snippet'] ?? '').toString(),
        );
      }).toList();

      return SearchResult(items: items);
    } catch (e) {
      throw Exception('TinyFish search failed: $e');
    }
  }
}

#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import argparse
import sys
import os

try:
    from neo4j import GraphDatabase
except ImportError:
    print("Error: The 'neo4j' Python driver is not installed.")
    print("Please install it using: pip install neo4j")
    sys.exit(1)

def migrate_to_neo4j(file_path, uri, user, password, clear_db=False):
    if not os.path.exists(file_path):
        print(f"Error: JSON file '{file_path}' does not exist.")
        sys.exit(1)

    print(f"Reading StoryBrain JSON from: {file_path}")
    with open(file_path, "r", encoding="utf-8") as f:
        try:
            brain_data = json.load(f)
        except Exception as e:
            print(f"Error parsing JSON file: {e}")
            sys.exit(1)

    # Validate root structure
    book_title = brain_data.get("bookTitle", "Unknown Book")
    # Use book title/ID to isolate data
    book_id = brain_data.get("bookId", f"book_{int(os.path.getmtime(file_path))}")
    print(f"Book Title: {book_title}")
    print(f"Book ID: {book_id}")

    driver = GraphDatabase.driver(uri, auth=(user, password))
    try:
        driver.verify_connectivity()
        print("Successfully connected to Neo4j database!")
    except Exception as e:
        print(f"Error connecting to Neo4j: {e}")
        driver.close()
        sys.exit(1)

    with driver.session() as session:
        # 1. Clear database elements for this book if requested
        if clear_db:
            print(f"Clearing existing database nodes and relationships for Book ID: {book_id}...")
            # Delete relationships and nodes associated with this bookId
            session.run("MATCH (n {bookId: $bookId}) DETACH DELETE n", bookId=book_id)
            session.run("MATCH (n:Book {id: $bookId}) DETACH DELETE n", bookId=book_id)
            print("Cleanup completed.")

        print("Importing Book node...")
        # 2. Create Book node
        session.run(
            """
            MERGE (b:Book {id: $bookId})
            SET b.title = $title,
                b.processedChapterCount = $processedChapterCount,
                b.totalChapters = $totalChapters
            """,
            bookId=book_id,
            title=book_title,
            processedChapterCount=brain_data.get("processedChapterCount", 0),
            totalChapters=brain_data.get("totalChapters", 0)
        )

        # 3. Create Character nodes (Global Registry)
        global_registry = brain_data.get("globalRegistry", {}).get("characters", {})
        print(f"Importing {len(global_registry)} characters from Global Registry...")
        for char_id, char_info in global_registry.items():
            aliases = char_info.get("aliases", [])
            name = char_info.get("name", "")
            avatar_seed = char_info.get("avatarSeed", name[0] if name else "书")

            session.run(
                """
                MERGE (b:Book {id: $bookId})
                MERGE (c:Character {id: $charId, bookId: $bookId})
                SET c.name = $name,
                    c.aliases = $aliases,
                    c.avatarSeed = $avatarSeed
                MERGE (b)-[:HAS_CHARACTER]->(c)
                """,
                bookId=book_id,
                charId=char_id,
                name=name,
                aliases=aliases,
                avatar_seed=avatar_seed
            )

        # 4. Create PlotNodes and relationships
        nodes = brain_data.get("nodes", {})
        print(f"Importing {len(nodes)} plot nodes and networks...")

        # Keep track of parental links to create later
        parent_child_links = []

        for plot_id, node_info in nodes.items():
            title = node_info.get("title", "")
            chapter_range = node_info.get("chapterRange", "")
            summary = node_info.get("summary", "")
            status = node_info.get("status", "active")
            narration_analysis = node_info.get("narrationAnalysis", "")

            # Create the plot node
            session.run(
                """
                MERGE (b:Book {id: $bookId})
                MERGE (p:PlotNode {id: $plotId, bookId: $bookId})
                SET p.title = $title,
                    p.chapterRange = $chapterRange,
                    p.summary = $summary,
                    p.status = $status,
                    p.narrationAnalysis = $narration_analysis
                MERGE (b)-[:HAS_PLOT]->(p)
                """,
                bookId=book_id,
                plotId=plot_id,
                title=title,
                chapter_range=chapter_range,
                summary=summary,
                status=status,
                narration_analysis=narration_analysis
            )

            # Collect parent-child references
            parents = node_info.get("parentNodes", [])
            for parent_id in parents:
                parent_child_links.append((parent_id, plot_id))

            # Import local network of characters in this plot node
            local_network = node_info.get("localNetwork", {})
            active_characters = local_network.get("activeCharacters", [])
            links = local_network.get("links", [])

            # Presents Character links
            for char_id in active_characters:
                session.run(
                    """
                    MATCH (p:PlotNode {id: $plotId, bookId: $bookId})
                    MATCH (c:Character {id: $charId, bookId: $bookId})
                    MERGE (p)-[:PRESENTS_CHARACTER]->(c)
                    """,
                    bookId=book_id,
                    plotId=plot_id,
                    charId=char_id
                )

            # Character interactions inside this scene
            for link in links:
                from_char = link.get("from", "")
                to_char = link.get("to", "")
                relation = link.get("relation", "")
                context = link.get("context", "")

                session.run(
                    """
                    MATCH (c1:Character {id: $fromChar, bookId: $bookId})
                    MATCH (c2:Character {id: $toChar, bookId: $bookId})
                    MERGE (c1)-[r:INTERACTS_WITH {plotId: $plotId, bookId: $bookId}]->(c2)
                    SET r.relation = $relation,
                        r.context = $context,
                        r.plotTitle = $plotTitle
                    """,
                    bookId=book_id,
                    plotId=plot_id,
                    fromChar=from_char,
                    toChar=to_char,
                    relation=relation,
                    context=context,
                    plotTitle=title
                )

        # 5. Create Plot Flow topology (NEXT_PLOT links)
        print("Importing plot topology connections...")
        created_topology_links = 0
        for parent_id, child_id in parent_child_links:
            # Check if parent and child exist in nodes
            if parent_id in nodes and child_id in nodes:
                session.run(
                    """
                    MATCH (p1:PlotNode {id: $parentId, bookId: $bookId})
                    MATCH (p2:PlotNode {id: $childId, bookId: $bookId})
                    MERGE (p1)-[:NEXT_PLOT]->(p2)
                    """,
                    bookId=book_id,
                    parentId=parent_id,
                    childId=child_id
                )
                created_topology_links += 1

        print(f"Successfully migrated StoryBrain to Neo4j!")
        print(f"Created/Merged: 1 Book Node, {len(global_registry)} Character Nodes, {len(nodes)} Plot Nodes.")
        print(f"Created/Merged: {created_topology_links} Plot Flow topology links.")

    driver.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Import StoryBrain JSON data into Neo4j graph database.")
    parser.add_argument("--file", "-f", required=True, help="Path to story_brain.json file")
    parser.add_argument("--uri", "-u", default="bolt://localhost:7687", help="Neo4j connection URI (default: bolt://localhost:7687)")
    parser.add_argument("--user", "-user", default="neo4j", help="Neo4j username (default: neo4j)")
    parser.add_argument("--password", "-p", required=True, help="Neo4j password")
    parser.add_argument("--clear", action="store_true", help="Clear existing nodes/edges for this book before importing")

    args = parser.parse_args()

    migrate_to_neo4j(
        file_path=args.file,
        uri=args.uri,
        user=args.user,
        password=args.password,
        clear_db=args.clear
    )

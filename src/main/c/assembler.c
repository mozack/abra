#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <iostream>
#include <stack>
#include <sparsehash/sparse_hash_map>
#include <sparsehash/sparse_hash_set>
#include <stdexcept>
#include "abra_NativeAssembler.h"

using namespace std;
using google::sparse_hash_map;
using google::sparse_hash_set;

#define READ_LENGTH 100
#define KMER 63
#define MIN_CONTIG_LENGTH 101
#define MIN_NODE_FREQUENCY 3
#define MAX_CONTIG_SIZE 10000

#define OK 0
#define TOO_MANY_PATHS_FROM_ROOT -1
#define TOO_MANY_CONTIGS -2
#define STOPPED_ON_REPEAT -3

struct eqstr
{
  bool operator()(const char* s1, const char* s2) const
  {
    return (s1 == s2) || (s1 && s2 && strcmp(s1, s2) == 0);
  }
};

struct my_hash
{
	unsigned long operator()(const char* s1) const
	{
		unsigned long hash = 0;
		int c;

		while((c = *s1++))
		{
			/* hash = hash * 33 ^ c */
			hash = ((hash << 5) + hash) ^ c;
		}

		return hash;
	}
};

struct struct_pool {
	struct node_pool* node_pool;
	struct kmer_pool* kmer_pool;
	struct read_pool* read_pool;
};

#define NODES_PER_BLOCK 10000
#define MAX_NODE_BLOCKS 50000
#define KMERS_PER_BLOCK 10000
#define MAX_KMER_BLOCKS 50000
#define READS_PER_BLOCK 10000
#define MAX_READ_BLOCKS 10000

struct node_pool {
	struct node** nodes;
	int block_idx;
	int node_idx;
};

struct kmer_pool {
	char** kmers;
	int block_idx;
	int kmer_idx;
};

struct read_pool {
	char** reads;
	int block_idx;
	int read_idx;
};

struct node {

	//TODO: Collapse from 8 to 2 bits.  Only store as key.
	char* seq;
	int frequency;
	//TODO: Convert to stl?
	struct linked_node* toNodes;
	struct linked_node* fromNodes;
	char* contributingRead;;
	char hasMultipleUniqueReads;
};

struct linked_node {
	struct node* node;
	struct linked_node* next;
};

int compare(const char* s1, const char* s2) {
	return (s1 == s2) || (s1 && s2 && strcmp(s1, s2) == 0);
}

struct struct_pool* init_pool() {
	struct_pool* pool = (struct struct_pool*) malloc(sizeof(struct_pool));
	pool->node_pool = (struct node_pool*) malloc(sizeof(node_pool));
	// Allocate array of arrays
	pool->node_pool->nodes = (struct node**) malloc(sizeof(struct node*) * MAX_NODE_BLOCKS);
	// Allocate first array of nodes
	pool->node_pool->nodes[0] = (struct node*) malloc(sizeof(struct node) * NODES_PER_BLOCK);
	pool->node_pool->block_idx = 0;
	pool->node_pool->node_idx = 0;

	pool->kmer_pool = (struct kmer_pool*) malloc(sizeof(kmer_pool));
	pool->kmer_pool->kmers = (char**) malloc(sizeof(char*) * MAX_KMER_BLOCKS);
	pool->kmer_pool->kmers[0] = (char*) malloc(sizeof(char) * (KMER+1) * KMERS_PER_BLOCK);
	pool->kmer_pool->block_idx = 0;
	pool->kmer_pool->kmer_idx = 0;

	pool->read_pool = (struct read_pool*) malloc(sizeof(read_pool));
	pool->read_pool->reads = (char**) malloc(sizeof(char*) * MAX_READ_BLOCKS);
	pool->read_pool->reads[0] = (char*) malloc(sizeof(char) * (READ_LENGTH+1) * READS_PER_BLOCK);
	pool->read_pool->block_idx = 0;
	pool->read_pool->read_idx = 0;

	return pool;
}

char* allocate_read(struct_pool* pool) {
	if (pool->read_pool->block_idx > MAX_READ_BLOCKS) {
		printf("READ BLOCK INDEX TOO BIG!!!!\n");
		exit(-1);
	}

	if (pool->read_pool->read_idx >= READS_PER_BLOCK) {
		pool->read_pool->block_idx++;
		pool->read_pool->read_idx = 0;
		pool->read_pool->reads[pool->read_pool->block_idx] = (char*) malloc(sizeof(char) * (READ_LENGTH+1) * READS_PER_BLOCK);
	}

	return &pool->read_pool->reads[pool->read_pool->block_idx][pool->read_pool->read_idx++ * (READ_LENGTH+1)];
}


char* allocate_kmer(struct_pool* pool) {
	if (pool->kmer_pool->block_idx > MAX_KMER_BLOCKS) {
		printf("KMER BLOCK INDEX TOO BIG!!!!\n");
		exit(-1);
	}

	if (pool->kmer_pool->kmer_idx >= KMERS_PER_BLOCK) {
		pool->kmer_pool->block_idx++;
		pool->kmer_pool->kmer_idx = 0;
//		printf("Allocating new block...\n");
		pool->kmer_pool->kmers[pool->kmer_pool->block_idx] = (char*) malloc(sizeof(char) * (KMER+1) * KMERS_PER_BLOCK);
	}

	return &pool->kmer_pool->kmers[pool->kmer_pool->block_idx][pool->kmer_pool->kmer_idx++ * (KMER+1)];
}

struct node* allocate_node(struct_pool* pool) {
	if (pool->node_pool->block_idx >= MAX_NODE_BLOCKS) {
		printf("NODE BLOCK INDEX TOO BIG!!!!\n");
		exit(-1);
	}

	if (pool->node_pool->node_idx >= NODES_PER_BLOCK) {
		pool->node_pool->block_idx++;
		pool->node_pool->node_idx = 0;
		pool->node_pool->nodes[pool->node_pool->block_idx] = (struct node*) malloc(sizeof(struct node) * NODES_PER_BLOCK);
	}

	return &pool->node_pool->nodes[pool->node_pool->block_idx][pool->node_pool->node_idx++];
}

struct node* new_node(char* seq, char* contributingRead, struct_pool* pool) {

//	node* my_node = (node*) malloc(sizeof(node));
	node* my_node = allocate_node(pool);
	memset(my_node, 0, sizeof(node));
	my_node->seq = seq;
//	strcpy(my_node->contributingRead, contributingRead);
	my_node->contributingRead = contributingRead;
	my_node->frequency = 1;
	my_node->hasMultipleUniqueReads = 0;
	return my_node;
}

char* get_kmer(int idx, char* sequence, struct struct_pool* pool) {
//	char* kmer = (char*) malloc(sizeof(char) * KMER+1);
	char* kmer = allocate_kmer(pool);
	memset(kmer, 0, KMER+1);

	memcpy(kmer, &sequence[idx], KMER);

	return kmer;
}

void unget_kmer(char* kmer, struct struct_pool* pool) {
	memset(kmer, 0, KMER+1);
	pool->kmer_pool->kmer_idx--;
}

int is_node_in_list(struct node* node, struct linked_node* list) {
	struct linked_node* ptr = list;

	while (ptr != NULL) {
		if (compare(ptr->node->seq, node->seq)) {
			return 1;
		}
		ptr = ptr->next;
	}

	return 0;
}

void link_nodes(struct node* from_node, struct node* to_node) {
	if (!is_node_in_list(to_node, from_node->toNodes)) {
		struct linked_node* to_link = (linked_node*) malloc(sizeof(linked_node));
		to_link->node = to_node;
		to_link->next = from_node->toNodes;
		from_node->toNodes = to_link;
	}

	if (!is_node_in_list(from_node, to_node->fromNodes)) {
		struct linked_node* from_link = (linked_node*) malloc(sizeof(linked_node));
		from_link->node = from_node;
		from_link->next = to_node->fromNodes;
		to_node->fromNodes = from_link;
	}
}

void increment_node_freq(struct node* node, char* read_seq) {
	node->frequency++;

	if (!(node->hasMultipleUniqueReads) && !compare(node->contributingRead, read_seq)) {
		node->hasMultipleUniqueReads = 1;
	}
}

void add_to_graph(char* sequence, sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes, struct_pool* pool) {

	struct node* prev = 0;

	for (int i=0; i<=READ_LENGTH-KMER; i++) {

		char* kmer = get_kmer(i, sequence, pool);
//		printf("\tkmer: %s\n", kmer);

		struct node* curr = (*nodes)[kmer];

		if (curr == NULL) {
			curr = new_node(kmer, sequence, pool);

			if (curr == NULL) {
				printf("Null node for kmer: %s\n", kmer);
				exit(-1);
			}

			(*nodes)[kmer] = curr;
		} else {
			unget_kmer(kmer, pool);
			increment_node_freq(curr, sequence);
		}

		if (prev != NULL) {
			link_nodes(prev, curr);
		}

		prev = curr;
	}
}

void build_graph(const char* read_file, sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes, struct_pool* pool) {
	FILE *fp = fopen(read_file, "r");
	char read[READ_LENGTH+1];
	memset(read, 0, READ_LENGTH+1);

	int line = 0;
	while (fscanf(fp, "%s", read) != EOF) {
//		printf("read: %d : %s\n", line++, read);
		if (strcmp(read, "") != 0) {
			char* read_ptr = allocate_read(pool);
			memcpy(read_ptr, read, READ_LENGTH+1);
			add_to_graph(read_ptr, nodes, pool);
			line++;

			if ((line % 100000) == 0) {
				printf("Processed %d reads.\n", line);
			}
		}
	}

	printf("Num reads: %d\n", line);
	printf("Num nodes: %d\n", nodes->size());

	fclose(fp);
}

struct linked_node* remove_node_from_list(struct node* node, struct linked_node* list) {
	struct linked_node* node_ptr = list;
	struct linked_node* prev_ptr = NULL;

	char is_found = false;
	while ((node_ptr != NULL) && (!is_found)) {
		if (strcmp(node_ptr->node->seq, node->seq) == 0) {
			if (prev_ptr == NULL) {
				// Set head of list to next elem
				list = list->next;
			} else {
				// Remove node from list
				prev_ptr->next = node_ptr->next;
			}

			// Free linked_node
			free(node_ptr);
			is_found = true;
		}

		prev_ptr = node_ptr;
		node_ptr = node_ptr->next;
	}

	return list;
}


void cleanup(struct linked_node* linked_nodes) {
	struct linked_node* ptr = linked_nodes;
	while (ptr != NULL) {
		struct linked_node* next = ptr->next;
		free(ptr);
		ptr = next;
	}
}

void prune_graph(sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes) {
	for (sparse_hash_map<const char*, struct node*, my_hash, eqstr>::const_iterator it = nodes->begin();
		         it != nodes->end(); ++it) {

		const char* key = it->first;
		struct node* node = it->second;

		if ((node != NULL) && ((node->frequency < MIN_NODE_FREQUENCY) || (!(node->hasMultipleUniqueReads)))) {

			// Remove node from "from" lists
			struct linked_node* to_node = node->toNodes;
			while (to_node != NULL) {
				to_node->node->fromNodes = remove_node_from_list(node, to_node->node->fromNodes);
				to_node = to_node->next;
			}

			// Remove node from "to" lists
			struct linked_node* from_node = node->fromNodes;
			while (from_node != NULL) {
				from_node->node->toNodes = remove_node_from_list(node, from_node->node->toNodes);
				from_node = from_node->next;
			}

			// Remove node from map
			nodes->erase(key);
			cleanup(node->toNodes);
			node->toNodes = NULL;
			cleanup(node->fromNodes);
			node->fromNodes = NULL;

			// Free memory
//			free(node);
		}
	}
}

struct linked_node* identify_root_nodes(sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes) {

	struct linked_node* root_nodes = NULL;
	int count = 0;

	for (sparse_hash_map<const char*, struct node*, my_hash, eqstr>::const_iterator it = nodes->begin();
	         it != nodes->end(); ++it) {
		struct node* node = it->second;
		if ((node != NULL) && (node->fromNodes == NULL)) {
			struct linked_node* next = root_nodes;
			root_nodes = (linked_node*) malloc(sizeof(linked_node));
			root_nodes->node = node;
			root_nodes->next = next;

//			printf("Root: %s\n", node->seq);
			count++;
		}
	}

	printf("num root nodes: %d\n", count);

	return root_nodes;
}

struct contig {
	char seq[MAX_CONTIG_SIZE];
	int size;
	char is_repeat;
	struct node* curr_node;
	sparse_hash_set<const char*, my_hash, eqstr>* visited_nodes;
};

struct contig* new_contig() {
	struct contig* curr_contig;
	curr_contig = (contig*) malloc(sizeof(contig));
//	printf("seq size: %d\n", sizeof(curr_contig->seq));
	memset(curr_contig->seq, 0, sizeof(curr_contig->seq));
	curr_contig->size = 0;
	curr_contig->is_repeat = 0;
	curr_contig->visited_nodes = new sparse_hash_set<const char*, my_hash, eqstr>();
	return curr_contig;
}

struct contig* copy_contig(struct contig* orig) {
	struct contig* copy = (contig*) malloc(sizeof(contig));
	memcpy(copy->seq, orig->seq, MAX_CONTIG_SIZE);
	strncpy(copy->seq, orig->seq, MAX_CONTIG_SIZE);
	copy->size = orig->size;
	copy->is_repeat = orig->is_repeat;
	copy->visited_nodes = new sparse_hash_set<const char*, my_hash, eqstr>(*orig->visited_nodes);
	return copy;
}

void free_contig(struct contig* contig) {
	delete contig->visited_nodes;
	memset(contig, 0, sizeof(contig));
	free(contig);
}

char is_node_visited(struct contig* contig, struct node* node) {
	 sparse_hash_set<const char*, my_hash, eqstr>::const_iterator it = contig->visited_nodes->find(node->seq);
	 return it != contig->visited_nodes->end();
}

void output_contig(struct contig* contig, int& contig_count, FILE* fp, const char* prefix) {
	if (strlen(contig->seq) >= MIN_CONTIG_LENGTH) {
		if (contig->is_repeat) {
			fprintf(fp, ">%s_%d_repeat\n%s\n", prefix, contig_count++, contig->seq);
		} else {
			fprintf(fp, ">%s_%d\n%s\n", prefix, contig_count++, contig->seq);
		}
	}
}

//#define OK 0
//#define TOO_MANY_PATHS_FROM_ROOT -1
//#define TOO_MANY_CONTIGS -2
//#define STOPPED_ON_REPEAT -3

int build_contigs(
		struct node* root,
		int& contig_count,
		FILE* fp,
		const char* prefix,
		int max_paths_from_root,
		int max_contigs,
		char stop_on_repeat,
		char shadow_mode) {

	int status = OK;
	stack<contig*> contigs;
	struct contig* root_contig = new_contig();
	root_contig->curr_node = root;
	contigs.push(root_contig);

	int paths_from_root = 1;

	while ((contigs.size() > 0) && (status == OK)) {
		// Get contig from stack
		struct contig* contig = contigs.top();

		if (is_node_visited(contig, contig->curr_node)) {
			// We've encountered a repeat
			contig->is_repeat = 1;
			if ((!shadow_mode) && (!stop_on_repeat)) {
				output_contig(contig, contig_count, fp, prefix);
			}
			contigs.pop();
			free_contig(contig);
			if (stop_on_repeat) {
				status = STOPPED_ON_REPEAT;
			}
		}
		else if (contig->curr_node->toNodes == NULL) {
			// We've reached the end of the contig.
			// Append entire current node.
			memcpy(&(contig->seq[contig->size]), contig->curr_node->seq, KMER);

			// Now, write the contig
			if (!shadow_mode) {
				output_contig(contig, contig_count, fp, prefix);
			}
			contigs.pop();
			free_contig(contig);
		}
		else {
			// Append first base from current node
			contig->seq[contig->size++] = contig->curr_node->seq[0];
			if (contig->size >= MAX_CONTIG_SIZE) {
				printf("Max contig size exceeded at node: %s\n", contig->curr_node->seq);
				exit(-1);
			}

			contig->visited_nodes->insert(contig->curr_node->seq);

			// Move current contig to next "to" node.
			struct linked_node* to_linked_node = contig->curr_node->toNodes;
			contig->curr_node = to_linked_node->node;
			paths_from_root++;

			// If there are multiple "to" nodes, branch the contig and push on stack
			to_linked_node = to_linked_node->next;
			while (to_linked_node != NULL) {
				//TODO: Do not clone contig for first node.
				struct contig* contig_branch = copy_contig(contig);
//				printf("orig size: %d, copy size: %d\n", contig->visited_nodes->size(), contig_branch->visited_nodes->size());
				contig_branch->curr_node = to_linked_node->node;
				contigs.push(contig_branch);
				to_linked_node = to_linked_node->next;
				paths_from_root++;
			}
		}

		if (contig_count >= max_contigs) {
			status = TOO_MANY_CONTIGS;
		}

		if (paths_from_root >= max_paths_from_root) {
			status = TOO_MANY_PATHS_FROM_ROOT;
		}
	}

	// Cleanup stranded contigs in case processing stopped.
	while (contigs.size() > 0) {
		struct contig* contig = contigs.top();
		contigs.pop();
		free_contig(contig);
	}

	return status;
}

void cleanup(sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes, struct struct_pool* pool) {

	// Free linked lists
	for (sparse_hash_map<const char*, struct node*, my_hash, eqstr>::const_iterator it = nodes->begin();
	         it != nodes->end(); ++it) {
		struct node* node = it->second;

		if (node != NULL) {
			cleanup(node->toNodes);
			cleanup(node->fromNodes);
		}
	}

	// Free nodes and keys
//	for (sparse_hash_map<const char*, struct node*, my_hash, eqstr>::const_iterator it = nodes->begin();
//	         it != nodes->end(); ++it) {
//		char* key = (char*) it->first;
//		struct node* node = it->second;
//
////		if (node != NULL) {
////			free(node);
////		}
//
//		if (key != NULL) {
//			free(key);
//		}
//	}
//

	for (int i=0; i<=pool->node_pool->block_idx; i++) {
		free(pool->node_pool->nodes[i]);
	}

	free(pool->node_pool->nodes);
	free(pool->node_pool);

	for (int i=0; i<=pool->kmer_pool->block_idx; i++) {
		free(pool->kmer_pool->kmers[i]);
	}

	free(pool->kmer_pool->kmers);
	free(pool->kmer_pool);

	for (int i=0; i<=pool->read_pool->block_idx; i++) {
		free(pool->read_pool->reads[i]);
	}

	free(pool->read_pool->reads);
	free(pool->read_pool);

	free(pool);
}

int assemble(const char* input,
			  const char* output,
			  const char* prefix,
			  int truncate_on_repeat,
			  int max_contigs,
			  int max_paths_from_root) {


	struct struct_pool* pool = init_pool();
	sparse_hash_map<const char*, struct node*, my_hash, eqstr>* nodes = new sparse_hash_map<const char*, struct node*, my_hash, eqstr>();

	long startTime = time(NULL);
	printf("Assembling: %s -> %s\n", input, output);
	nodes->set_deleted_key(NULL);

	build_graph(input, nodes, pool);
	prune_graph(nodes);

	struct linked_node* root_nodes = identify_root_nodes(nodes);

	int contig_count = 0;
	char truncate_output = 0;

	FILE *fp = fopen(output, "w");
	while (root_nodes != NULL) {

		int shadow_count = 0;

		// Run in shadow mode first
		int status = build_contigs(root_nodes->node, shadow_count, fp, prefix, max_paths_from_root, max_contigs, truncate_on_repeat, true);

		if (status == OK) {
			// Now output the contigs
			build_contigs(root_nodes->node, contig_count, fp, prefix, max_paths_from_root, max_contigs, truncate_on_repeat, false);
		}

		switch(status) {
			case TOO_MANY_CONTIGS:
				printf("TOO_MANY_CONTIGS: %s\n", prefix);
				contig_count = 0;
				break;
			case STOPPED_ON_REPEAT:
				printf("STOPPED_ON_REPEAT: %s\n", prefix);
				contig_count = 0;
				break;
			case TOO_MANY_PATHS_FROM_ROOT:
				printf("TOO_MANY_PATHS_FROM_ROOT: %s - %s\n", prefix, root_nodes->node->seq);
				break;
		}

		// If too many contigs or abort due to repeat, break out of loop and truncate output.
		if ((status == TOO_MANY_CONTIGS) || (status == STOPPED_ON_REPEAT)) {
			truncate_output = 1;
			break;
		}

		root_nodes = root_nodes->next;
	}
	fclose(fp);


	if (truncate_output) {
		FILE *fp = fopen(output, "w");
		fclose(fp);
	}

	cleanup(nodes, pool);

	delete nodes;

	long stopTime = time(NULL);
	printf("Done assembling(%ld): %s -> %s\n", (stopTime-startTime), input, output);

	return contig_count;
}

extern "C"
 JNIEXPORT jint JNICALL Java_abra_NativeAssembler_assemble
   (JNIEnv *env, jobject obj, jstring j_input, jstring j_output, jstring j_prefix,
    jint j_truncate_on_output, jint j_max_contigs, jint j_max_paths_from_root)
 {
     //Get the native string from javaString
     //const char *nativeString = env->GetStringUTFChars(javaString, 0);
	const char* input  = env->GetStringUTFChars(j_input, 0);
	const char* output = env->GetStringUTFChars(j_output, 0);
	const char* prefix = env->GetStringUTFChars(j_prefix, 0);
	int truncate_on_output = j_truncate_on_output;
	int max_contigs = j_max_contigs;
	int max_paths_from_root = j_max_paths_from_root;

	printf("Abra JNI entry point\n");

	printf("input: %s\n", input);
	printf("output: %s\n", output);
	printf("prefix: %s\n", prefix);
	printf("truncate_on_output: %d\n", truncate_on_output);
	printf("max_contigs: %d\n", max_contigs);
	printf("max_paths_from_root: %d\n", max_paths_from_root);

	int ret = assemble(input, output, prefix, truncate_on_output, max_contigs, max_paths_from_root);

     //DON'T FORGET THIS LINE!!!
    env->ReleaseStringUTFChars(j_input, input);
    env->ReleaseStringUTFChars(j_output, output);
    env->ReleaseStringUTFChars(j_prefix, prefix);

    fflush(stdout);
    return ret;
 }


/*
//extern "C"
JNIEXPORT void JNICALL Java_abra_NativeAssembler_assemble
  (JNIEnv * env, jobject obj, jstring j_input, jstring j_output, jstring j_prefix)
// JNIEXPORT void JNICALL Java_abra_NativeAssembler_assemble
//   (JNIEnv *env, jobject obj, jstring j_input, jstring j_output, jstring j_prefix)
 {
     //Get the native string from javaString
     //const char *nativeString = env->GetStringUTFChars(javaString, 0);
	const char* input  = env->GetStringUTFChars(j_input, 0);
	const char* output = env->GetStringUTFChars(j_output, 0);
	const char* prefix = env->GetStringUTFChars(j_prefix, 0);

	printf("input: %s\n", input);
	printf("output: %s\n", output);
	printf("prefix: %s\n", prefix);

     //DON'T FORGET THIS LINE!!!
    env->ReleaseStringUTFChars(j_input, input);
    env->ReleaseStringUTFChars(j_output, output);
    env->ReleaseStringUTFChars(j_prefix, prefix);
 }
 */

int main(int argc, char* argv[]) {

/*
	assemble(
		"/home/lmose/code/abra/src/main/c/sim83/unaligned.bam.reads",
		"/home/lmose/code/abra/src/main/c/unaligned_c.fa",
		"foo",
		false,
		50000,
		5000);
*/


	/*
	assemble(
		"/home/lmose/code/abra/src/main/c/sim83_reads_filtered.txt",
		"/home/lmose/code/abra/src/main/c/sim83_c.fasta",
		"foo",
		false,
		50000,
		5000);
		*/

	assemble(
		"/home/lmose/code/abra/src/main/c/sim83/sm/reads.txt",
		"/home/lmose/code/abra/src/main/c/sim83/sm/reads.fa",
		"foo",
		false,
		50000,
		5000);

	/*
	assemble(
		argv[1],
		argv[2],
		"foo",
		false,
		50000,
		5000);
*/
	/*
	assemble(
		argv[1],
		argv[2],
		"foo",
		false,
		50000,
		5000);
*/

	/*
	assemble(
		"/home/lmose/code/abra/src/main/c/sim83/250000.txt",
		"/home/lmose/code/abra/src/main/c/sim83/250000.fa",
		"foo",
		false,
		5000000,
		5000);
		*/

/*
	assemble(
		"/home/lmose/code/abra/src/main/c/sim83/500000.txt",
		"/home/lmose/code/abra/src/main/c/sim83/500000.fa",
		"foo",
		false,
		5000000,
		5000);
*/

	/*
	assemble(
		"/home/lmose/code/abra/src/main/c/sim83/filtered.txt",
		"/home/lmose/code/abra/src/main/c/sim83/filtered.fa",
		"foo",
		false,
		5000000,
		5000);
*/

/*
	for (int i=0; i<50; i++) {
		char file[200];
		memset(file, 0, sizeof(file));
		sprintf(file, "%s_%d", "/home/lmose/code/abra/src/main/c/sim83/250000_c_run", i);
		assemble(
			"/home/lmose/code/abra/src/main/c/sim83/250000.txt",
			file,
			"foo",
			false,
			1000000,
			5000);
	}
*/
}
